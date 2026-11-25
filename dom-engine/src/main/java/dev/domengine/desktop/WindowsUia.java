package dev.domengine.desktop;

import dev.domengine.cdp.CdpException;
import dev.domengine.tree.DomNode;

import java.util.Map;

/** Windows UI Automation. PowerShell drives the real AutomationElement tree. Off-platform calls fail before PowerShell. */
final class WindowsUia {
    private WindowsUia() {
    }

    static void requireWindows() {
        if (!"windows".equals(NativeDesktop.family())) throw new CdpException("requires Windows");
    }

    static String captureScript() {
        return """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                Add-Type -AssemblyName UIAutomationClient
                $script:count = 0
                $script:truncated = $false
                $script:sawEdit = $false
                function Clean([string]$value) {
                  if ($null -eq $value) { return '' }
                  return (($value -replace "[`t`r`n]", ' '))
                }
                function Emit($el, [int]$depth) {
                  if ($null -eq $el) { return }
                  if ($depth -gt 12 -or $script:count -ge 500) { $script:truncated = $true; return }
                  $script:count++
                  $name = ''
                  try { $name = [string]$el.Current.Name } catch {}
                  $role = 'unknown'
                  try {
                    $raw = [string]$el.Current.ControlType.ProgrammaticName
                    if ($raw.StartsWith('ControlType.')) { $role = $raw.Substring(12).ToLowerInvariant() } else { $role = $raw.ToLowerInvariant() }
                    if ($role -eq 'edit' -or $role -eq 'document' -or $role -eq 'text') { $script:sawEdit = $true }
                  } catch {}
                  $x = 0; $y = 0; $w = 0; $h = 0
                  try {
                    $b = $el.Current.BoundingRectangle
                    $x = [int]$b.X; $y = [int]$b.Y; $w = [int]$b.Width; $h = [int]$b.Height
                  } catch {}
                  $id = ''
                  try { $id = (($el.GetRuntimeId() | ForEach-Object { $_.ToString() }) -join '.') } catch {}
                  $auto = ''
                  try { $auto = [string]$el.Current.AutomationId } catch {}
                  $className = ''
                  try { $className = [string]$el.Current.ClassName } catch {}
                  # edit-class-is-textbox: WinForms EDIT is ControlType.Pane (WindowsForms10.EDIT).
                  if ($className -and ($role -eq 'pane' -or $role -eq 'custom' -or $role -eq 'unknown' -or $role -eq 'group') -and $className.ToLowerInvariant().Contains('edit')) {
                    $role = 'edit'
                    $script:sawEdit = $true
                  }
                  $help = ''
                  try { $help = [string]$el.Current.HelpText } catch {}
                  $states = @()
                  try { if (-not $el.Current.IsEnabled) { $states += 'disabled' } } catch {}
                  try { if ($el.Current.HasKeyboardFocus) { $states += 'focused' } } catch {}
                  try { if ($el.Current.IsOffscreen) { $states += 'offscreen' } } catch {}
                  try {
                    $toggle = $el.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
                    if ([string]$toggle.Current.ToggleState -eq 'On') { $states += 'checked' }
                  } catch {}
                  try {
                    $expand = $el.GetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern)
                    if ([string]$expand.Current.ExpandCollapseState -eq 'Expanded') { $states += 'expanded' }
                  } catch {}
                  $labeled = ''
                  try {
                    $by = $el.GetCurrentPropertyValue([System.Windows.Automation.AutomationElement]::LabeledByProperty)
                    if ($null -ne $by) { $labeled = (($by.GetRuntimeId() | ForEach-Object { $_.ToString() }) -join '.') }
                  } catch {}
                  Write-Output ("NODE`t{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}`t{7}" -f $depth, $role, $x, $y, $w, $h, $id, (Clean $name))
                  Write-Output ("STATE`t{0}`t{1}" -f $id, ($states -join ','))
                  if ($auto) { Write-Output ("ATTR`t{0}`tautomation-id`t{1}" -f $id, (Clean $auto)) }
                  if ($className) { Write-Output ("ATTR`t{0}`tclass-name`t{1}" -f $id, (Clean $className)) }
                  if ($help) { Write-Output ("ATTR`t{0}`thelp-text`t{1}" -f $id, (Clean $help)) }
                  if ($labeled) { Write-Output ("REL`t{0}`tlabeled-by`t{1}" -f $id, $labeled) }
                  if ($depth -ge 12) { return }
                  $kids = @()
                  try { $kids = $el.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition) } catch { $kids = @() }
                  if ($kids.Count -eq 0) {
                    $raw = [System.Windows.Automation.TreeWalker]::RawViewWalker
                    $child = $null
                    try { $child = $raw.GetFirstChild($el) } catch { $child = $null }
                    while ($null -ne $child) {
                      Emit $child ($depth + 1)
                      try { $child = $raw.GetNextSibling($child) } catch { break }
                    }
                    return
                  }
                  foreach ($kid in $kids) { Emit $kid ($depth + 1) }
                }
                $rootEl = [System.Windows.Automation.AutomationElement]::RootElement
                $want = [string]$env:DESKTOP_WINDOW
                if ($want) {
                  Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public class PkbPickWindow {
  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
  public static IntPtr Found;
  public static string Want;
  static EnumProc callback;
  public static bool Visit(IntPtr hWnd, IntPtr lParam) {
    StringBuilder sb = new StringBuilder(512);
    GetWindowText(hWnd, sb, sb.Capacity);
    string title = sb.ToString();
    if (title.Length == 0 || string.IsNullOrEmpty(Want)) return true;
    if (title.IndexOf(Want, StringComparison.OrdinalIgnoreCase) < 0) return true;
    Found = hWnd;
    return false;
  }
  public static bool Pick(string want) {
    Want = want;
    Found = IntPtr.Zero;
    callback = Visit;
    EnumWindows(callback, IntPtr.Zero);
    return Found != IntPtr.Zero;
  }
}
'@
                  Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public class PkbChildWindows {
  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr parent, EnumProc cb, IntPtr lParam);
  public static List<IntPtr> Kids = new List<IntPtr>();
  static EnumProc callback;
  public static bool Visit(IntPtr hWnd, IntPtr lParam) {
    Kids.Add(hWnd);
    return true;
  }
  public static IntPtr[] List(IntPtr parent) {
    Kids = new List<IntPtr>();
    callback = Visit;
    EnumChildWindows(parent, callback, IntPtr.Zero);
    return Kids.ToArray();
  }
}
'@
                  if ([PkbPickWindow]::Pick($want)) {
                    $picked = [System.Windows.Automation.AutomationElement]::FromHandle([PkbPickWindow]::Found)
                    if ($null -eq $picked) { Write-Error "not found: $want"; exit 1 }
                    Emit $picked 0
                    if (-not $script:sawEdit) {
                      foreach ($childHwnd in [PkbChildWindows]::List([PkbPickWindow]::Found)) {
                        $childEl = [System.Windows.Automation.AutomationElement]::FromHandle($childHwnd)
                        if ($null -ne $childEl) { Emit $childEl 1 }
                      }
                    }
                    if ($script:truncated) { Write-Output 'TRUNCATED' }
                    exit 0
                  }
                  function NameMatch($el) {
                    if ($null -eq $el) { return $false }
                    $name = ''
                    try { $name = [string]$el.Current.Name } catch { return $false }
                    return $name -and $name.ToLowerInvariant().Contains($want.ToLowerInvariant())
                  }
                  function Seek($el, [int]$depth) {
                    if ($null -eq $el -or $depth -gt 3) { return $null }
                    if ((NameMatch $el) -and $depth -gt 0) { return $el }
                    if ($depth -ge 3) { return $null }
                    $kids = @()
                    try { $kids = $el.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition) } catch { return $null }
                    foreach ($kid in $kids) {
                      $hit = Seek $kid ($depth + 1)
                      if ($null -ne $hit) { return $hit }
                    }
                    return $null
                  }
                  $named = Seek $rootEl 0
                  if ($null -eq $named) { Write-Error "not found: $want"; exit 1 }
                  Emit $named 0
                  if ($script:truncated) { Write-Output 'TRUNCATED' }
                  exit 0
                }
                Emit $rootEl 0
                if ($script:truncated) { Write-Output 'TRUNCATED' }
                """;
    }

    static String clickScript() {
        return """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                Add-Type -AssemblyName UIAutomationClient
                Add-Type @"
                using System;
                using System.Runtime.InteropServices;
                public static class DesktopInput {
                  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
                  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
                }
                "@
                $want = [string]$env:DESKTOP_UIA_ID
                function Find($el) {
                  if ($null -eq $el) { return $null }
                  $id = ''
                  try { $id = (($el.GetRuntimeId() | ForEach-Object { $_.ToString() }) -join '.') } catch {}
                  if ($id -eq $want) { return $el }
                  $kids = @()
                  try { $kids = $el.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition) } catch { $kids = @() }
                  foreach ($kid in $kids) {
                    $hit = Find $kid
                    if ($null -ne $hit) { return $hit }
                  }
                  return $null
                }
                $el = Find ([System.Windows.Automation.AutomationElement]::RootElement)
                if ($null -eq $el) { Write-Error "click failed: UIA element $want was not found"; exit 1 }
                try {
                  $invoke = $el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
                  $invoke.Invoke()
                  Write-Output 'invoked'
                  exit 0
                } catch {}
                try {
                  $b = $el.Current.BoundingRectangle
                  if ($b.Width -le 0 -or $b.Height -le 0) { throw 'no bounds' }
                  $x = [int]($b.X + ($b.Width / 2))
                  $y = [int]($b.Y + ($b.Height / 2))
                  if (-not [DesktopInput]::SetCursorPos($x, $y)) { throw 'SetCursorPos failed' }
                  [DesktopInput]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero)
                  [DesktopInput]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero)
                  Write-Output 'clicked'
                  exit 0
                } catch {
                  Write-Error "click failed: no InvokePattern and pointer input failed ($($_.Exception.Message))"
                  exit 1
                }
                """;
    }

    static String typeScript() {
        return """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                Add-Type -AssemblyName UIAutomationClient
                Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                public class PkbSend {
                  [StructLayout(LayoutKind.Explicit, Size = 40)]
                  public struct INPUT {
                    [FieldOffset(0)] public uint type;
                    [FieldOffset(8)] public ushort wVk;
                    [FieldOffset(10)] public ushort wScan;
                    [FieldOffset(12)] public uint dwFlags;
                    [FieldOffset(16)] public uint time;
                    [FieldOffset(24)] public IntPtr dwExtraInfo;
                  }
                  [DllImport("user32.dll", SetLastError = true)]
                  public static extern uint SendInput(uint nInputs, INPUT[] inputs, int cbSize);
                  [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "SendMessageW")]
                  public static extern IntPtr SendSet(IntPtr hWnd, uint msg, IntPtr wParam, string lParam);
                  [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "SendMessageW")]
                  public static extern IntPtr SendGet(IntPtr hWnd, uint msg, IntPtr wParam, System.Text.StringBuilder lParam);
                  public static string GetText(IntPtr hwnd) {
                    System.Text.StringBuilder sb = new System.Text.StringBuilder(4000);
                    SendGet(hwnd, 0x000D, new IntPtr(sb.Capacity), sb);
                    return sb.ToString();
                  }
                  public static void SetText(IntPtr hwnd, string text) {
                    SendSet(hwnd, 0x000C, IntPtr.Zero, text ?? "");
                  }
                  public static void Type(string text) {
                    if (text == null) text = "";
                    INPUT[] inputs = new INPUT[text.Length * 2];
                    for (int i = 0; i < text.Length; i++) {
                      inputs[i * 2].type = 1;
                      inputs[i * 2].wScan = (ushort)text[i];
                      inputs[i * 2].dwFlags = 4;
                      inputs[i * 2 + 1].type = 1;
                      inputs[i * 2 + 1].wScan = (ushort)text[i];
                      inputs[i * 2 + 1].dwFlags = 6;
                    }
                    if (inputs.Length == 0) return;
                    uint sent = SendInput((uint)inputs.Length, inputs, 40);
                    if (sent != (uint)inputs.Length) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
                  }
                }
                '@
                $want = [string]$env:DESKTOP_UIA_ID
                $text = [string]$env:DESKTOP_TEXT
                $append = [string]$env:DESKTOP_APPEND
                function Find($el) {
                  if ($null -eq $el) { return $null }
                  $id = ''
                  try { $id = (($el.GetRuntimeId() | ForEach-Object { $_.ToString() }) -join '.') } catch {}
                  if ($id -eq $want) { return $el }
                  $kids = @()
                  try { $kids = $el.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition) } catch { $kids = @() }
                  foreach ($kid in $kids) {
                    $hit = Find $kid
                    if ($null -ne $hit) { return $hit }
                  }
                  return $null
                }
                $el = Find ([System.Windows.Automation.AutomationElement]::RootElement)
                if ($null -eq $el) { Write-Error "type failed: UIA element $want was not found"; exit 1 }
                try {
                  $value = $el.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
                  $next = $text
                  if ($append -eq 'true') { $next = [string]$value.Current.Value + $text }
                  $value.SetValue($next)
                  Write-Output 'valued'
                  exit 0
                } catch {}
                try {
                  $hwnd = [IntPtr]$el.Current.NativeWindowHandle
                  if ($hwnd -ne [IntPtr]::Zero) {
                    $viaHwnd = $text
                    if ($append -eq 'true') { $viaHwnd = [PkbSend]::GetText($hwnd) + $text }
                    [PkbSend]::SetText($hwnd, $viaHwnd)
                    Write-Output 'wmsettext'
                    exit 0
                  }
                } catch {}
                try {
                  $pattern = $el.GetCurrentPattern([System.Windows.Automation.TextPattern]::Pattern)
                  $pattern.DocumentRange.Select()
                  $el.SetFocus()
                  [PkbSend]::Type($text)
                  Write-Output 'textpattern'
                  exit 0
                } catch {}
                try {
                  $el.SetFocus()
                  [PkbSend]::Type($text)
                  Write-Output 'sendinput'
                  exit 0
                } catch {
                  Write-Error "type failed: no ValuePattern, TextPattern, or SendInput ($($_.Exception.Message))"
                  exit 1
                }
                """;
    }

    static String foregroundScript() {
        return """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                using System.Text;
                public class PkbForeground {
                  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
                  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
                  public static string Title() {
                    StringBuilder sb = new StringBuilder(512);
                    GetWindowText(GetForegroundWindow(), sb, sb.Capacity);
                    return sb.ToString();
                  }
                }
                '@
                Write-Output ([PkbForeground]::Title())
                exit 0
                """;
    }

    /** Empty when this is not Windows or the foreground title cannot be read. */
    static String foregroundTitle() {
        if (!"windows".equals(NativeDesktop.family())) return "";
        try {
            NativeDesktop.CommandResult result = powershell(foregroundScript(), Map.of());
            if (result.timedOut() || result.exit() != 0) return "";
            String title = NativeDesktop.oneLine(result.output());
            return "no output".equals(title) ? "" : title;
        } catch (RuntimeException failure) {
            return "";
        }
    }

    static DomNode capture(String windowName) {
        requireWindows();
        java.util.Map<String, String> env = windowName == null || windowName.isBlank()
                ? Map.of() : Map.of("DESKTOP_WINDOW", windowName);
        NativeDesktop.CommandResult result = powershell(captureScript(), env);
        if (result.timedOut()) throw new CdpException("UIA unavailable: PowerShell timed out");
        if (result.exit() != 0) throw new CdpException("UIA unavailable: " + NativeDesktop.oneLine(result.output()));
        DomNode root = NativeDesktop.parseLines(result.output(), "uia");
        DesktopAnnotations.apply(root, result.output(), "uia-runtime-id");
        return root;
    }

    static void click(DomNode node) {
        requireWindows();
        String id = node.attributes.getOrDefault("uia-runtime-id", "");
        if (id.isBlank()) throw new CdpException("click failed: live desktop node is missing uia-runtime-id");
        NativeDesktop.CommandResult result = powershell(clickScript(), Map.of("DESKTOP_UIA_ID", id), 20);
        if (result.timedOut() || result.exit() != 0) {
            throw new CdpException("click failed: " + NativeDesktop.useful(result.output()));
        }
    }

    static void type(DomNode node, String text, boolean append) {
        requireWindows();
        String id = node.attributes.getOrDefault("uia-runtime-id", "");
        if (id.isBlank()) throw new CdpException("type failed: live desktop node is missing uia-runtime-id");
        NativeDesktop.CommandResult result = powershell(typeScript(), Map.of(
                "DESKTOP_UIA_ID", id,
                "DESKTOP_TEXT", text == null ? "" : text,
                "DESKTOP_APPEND", append ? "true" : "false"
        ), 20);
        if (result.timedOut() || result.exit() != 0) {
            throw new CdpException("type failed: " + NativeDesktop.useful(result.output()));
        }
    }

    private static NativeDesktop.CommandResult powershell(String script, Map<String, String> env) {
        return powershell(script, env, NativeDesktop.DEFAULT_TIMEOUT_SECONDS);
    }

    private static NativeDesktop.CommandResult powershell(String script, Map<String, String> env, int timeoutSeconds) {
        // powershell.exe -Command reparses the raw command line and drops quotes, so
        // a character class such as "[`t`r`n]" becomes a type literal. -EncodedCommand
        // is the script as UTF-16LE Base64, and the quotes and backticks stay.
        String encoded = java.util.Base64.getEncoder().encodeToString(
                script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        return NativeDesktop.run(java.util.List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded
        ), env, timeoutSeconds);
    }
}
