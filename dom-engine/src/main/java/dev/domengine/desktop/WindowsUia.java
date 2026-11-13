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
                Add-Type -AssemblyName UIAutomationClient
                $script:count = 0
                $script:truncated = $false
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
                  } catch {}
                  $x = 0; $y = 0; $w = 0; $h = 0
                  try {
                    $b = $el.Current.BoundingRectangle
                    $x = [int]$b.X; $y = [int]$b.Y; $w = [int]$b.Width; $h = [int]$b.Height
                  } catch {}
                  $id = ''
                  try { $id = (($el.GetRuntimeId() | ForEach-Object { $_.ToString() }) -join '.') } catch {}
                  Write-Output ("NODE`t{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}`t{7}" -f $depth, $role, $x, $y, $w, $h, $id, (Clean $name))
                  if ($depth -ge 12) { return }
                  $kids = @()
                  try { $kids = $el.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition) } catch { $kids = @() }
                  foreach ($kid in $kids) { Emit $kid ($depth + 1) }
                }
                Emit ([System.Windows.Automation.AutomationElement]::RootElement) 0
                if ($script:truncated) { Write-Output 'TRUNCATED' }
                """;
    }

    static String clickScript() {
        return """
                $ErrorActionPreference = 'Stop'
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
                  try { $kids = $el.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition) } catch { return $null }
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
                Add-Type -AssemblyName UIAutomationClient
                Add-Type -AssemblyName System.Windows.Forms
                $want = [string]$env:DESKTOP_UIA_ID
                $text = [string]$env:DESKTOP_TEXT
                $append = [string]$env:DESKTOP_APPEND
                function Find($el) {
                  if ($null -eq $el) { return $null }
                  $id = ''
                  try { $id = (($el.GetRuntimeId() | ForEach-Object { $_.ToString() }) -join '.') } catch {}
                  if ($id -eq $want) { return $el }
                  $kids = @()
                  try { $kids = $el.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition) } catch { return $null }
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
                  $el.SetFocus()
                  [System.Windows.Forms.SendKeys]::SendWait($text)
                  Write-Output 'keyed'
                  exit 0
                } catch {
                  Write-Error "type failed: no ValuePattern and SendKeys failed ($($_.Exception.Message))"
                  exit 1
                }
                """;
    }

    static DomNode capture(String windowName) {
        requireWindows();
        NativeDesktop.CommandResult result = powershell(captureScript(), Map.of());
        if (result.timedOut()) throw new CdpException("UIA unavailable: PowerShell timed out");
        if (result.exit() != 0) throw new CdpException("UIA unavailable: " + NativeDesktop.oneLine(result.output()));
        return NativeDesktop.parseLines(result.output(), "uia");
    }

    static void click(DomNode node) {
        requireWindows();
        String id = node.attributes.getOrDefault("uia-runtime-id", "");
        if (id.isBlank()) throw new CdpException("click failed: live desktop node is missing uia-runtime-id");
        NativeDesktop.CommandResult result = powershell(clickScript(), Map.of("DESKTOP_UIA_ID", id));
        if (result.timedOut() || result.exit() != 0) {
            throw new CdpException("click failed: " + NativeDesktop.oneLine(result.output()));
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
        ));
        if (result.timedOut() || result.exit() != 0) {
            throw new CdpException("type failed: " + NativeDesktop.oneLine(result.output()));
        }
    }

    private static NativeDesktop.CommandResult powershell(String script, Map<String, String> env) {
        return NativeDesktop.run(java.util.List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script
        ), env);
    }
}
