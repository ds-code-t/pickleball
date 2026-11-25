package dev.domengine.desktop;

import dev.domengine.cdp.CdpException;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.query.Query;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveDesktopTest {
    @AfterEach
    void reset() {
        DesktopTree.resetForTests();
    }

    @Test
    void liveAttachFailsClearlyAndForeignPlatformsDoNotRun() {
        String family = NativeDesktop.family();
        AtSpi.useSessionBusForTests("unix:path=/tmp/pickleball-no-atspi-bus");
        CliResult live = DomEngineMain.dispatch(List.of("desktop", "attach", "--id", "live1", "--live"));
        if ("linux".equals(family)) {
            assertEquals(1, live.exit(), live.stderr());
            assertTrue(live.stderr().contains("AT-SPI unavailable"), live.stderr());
            assertNoSuccessfulAttach(live);
        } else {
            assertFalse(live.stderr().contains("AT-SPI unavailable"), live.stderr());
            assertFalse(live.stderr().contains("Missing type name"), live.stderr());
            if ("windows".equals(family) && live.exit() != 0) {
                assertEquals(1, live.exit(), live.stderr());
                assertTrue(live.stderr().contains("UIA unavailable"), live.stderr());
                assertNoSuccessfulAttach(live);
            } else if ("macos".equals(family) && live.exit() != 0) {
                assertEquals(1, live.exit(), live.stderr());
                assertNoSuccessfulAttach(live);
                assertTrue(live.stderr().contains("osascript") || live.stderr().contains("requires")
                        || live.stderr().contains("unavailable"), live.stderr());
            } else {
                assertEquals(0, live.exit(), live.stderr());
                assertTrue(live.stdout().contains("\"ok\": true") || live.stdout().contains("\"ok\":true")
                        || live.stdout().contains("\"ok\" : true"), live.stdout());
                assertTrue(live.stdout().contains(family), live.stdout());
            }
        }

        if ("linux".equals(family)) {
            CdpException windows = assertThrows(CdpException.class, () -> WindowsUia.capture(""));
            assertTrue(windows.getMessage().contains("requires Windows"), windows.getMessage());
            CdpException mac = assertThrows(CdpException.class, () -> MacAccessibility.capture(""));
            assertTrue(mac.getMessage().contains("requires macOS"), mac.getMessage());
        } else if ("windows".equals(family)) {
            try {
                assertTrue(WindowsUia.capture("") != null);
            } catch (CdpException failure) {
                assertTrue(failure.getMessage().contains("UIA unavailable"), failure.getMessage());
                assertFalse(failure.getMessage().contains("Missing type name"), failure.getMessage());
                assertFalse(failure.getMessage().contains("requires Windows"), failure.getMessage());
            }
            CdpException mac = assertThrows(CdpException.class, () -> MacAccessibility.capture(""));
            assertTrue(mac.getMessage().contains("requires macOS"), mac.getMessage());
            CdpException linux = assertThrows(CdpException.class, () -> AtSpi.capture(""));
            assertTrue(linux.getMessage().contains("requires Linux"), linux.getMessage());
        } else {
            CdpException windows = assertThrows(CdpException.class, () -> WindowsUia.capture(""));
            assertTrue(windows.getMessage().contains("requires Windows"), windows.getMessage());
            CdpException linux = assertThrows(CdpException.class, () -> AtSpi.capture(""));
            assertTrue(linux.getMessage().contains("requires Linux"), linux.getMessage());
        }

        DomNode node = new DomNode();
        node.attributes.put("desktop-backend", "atspi");
        node.attributes.put("atspi-dest", "org.a11y.atspi.Registry");
        node.attributes.put("atspi-path", "/org/a11y/atspi/accessible/root");
        CdpException click = assertThrows(CdpException.class, () -> NativeDesktop.click(node));
        if ("linux".equals(family)) {
            assertTrue(click.getMessage().contains("AT-SPI"), click.getMessage());
        } else {
            assertTrue(click.getMessage().contains("requires Linux"), click.getMessage());
        }

        assertTrue(WindowsUia.captureScript().contains("UIAutomationClient"));
        assertTrue(WindowsUia.captureScript().contains("EnumWindows"));
        assertTrue(WindowsUia.captureScript().contains("not found:"));
        assertTrue(WindowsUia.captureScript().contains("edit-class-is-textbox"));
        assertTrue(WindowsUia.clickScript().contains("InvokePattern"));
        assertTrue(WindowsUia.typeScript().contains("ValuePattern"));
        assertTrue(WindowsUia.typeScript().contains("TextPattern"));
        assertTrue(WindowsUia.typeScript().contains("SendInput"));
        assertTrue(MacAccessibility.captureScript().contains("System Events"));
        assertTrue(MacAccessibility.clickScript().contains("AXPress"));
        assertEquals(12, NativeDesktop.WALK_DEPTH);
        assertEquals(500, NativeDesktop.WALK_NODES);
        assertTrue(NativeDesktop.platformId().startsWith(family + "-"), NativeDesktop.platformId());
    }

    private static void assertNoSuccessfulAttach(CliResult live) {
        assertFalse(live.stderr().contains("\"ok\" : true"), live.stderr());
        assertFalse(live.stdout().contains("\"ok\" : true"), live.stdout());
        assertFalse(live.stdout().contains("\"ok\":true"), live.stdout());
        assertFalse(live.stderr().contains("\"ok\": true"), live.stderr());
        assertFalse(live.stdout().contains("\"ok\": true"), live.stdout());
    }

    @Test
    void atspiRepliesAndLineTreesMapOntoTheSharedNode() {
        String sample = """
                method return time=1 sender=:1.0 -> destination=:1.1 serial=2 reply_serial=1
                   array [
                      struct {
                         string ":1.23"
                         object path "/org/a11y/atspi/accessible/0"
                      }
                      struct {
                         string ":1.24"
                         object path "/org/a11y/atspi/accessible/root"
                      }
                   ]
                """;
        assertEquals(2, AtSpi.objectRefs(sample).size());
        assertEquals(":1.23", AtSpi.objectRefs(sample).get(0).dest());
        assertEquals("/org/a11y/atspi/accessible/0", AtSpi.objectRefs(sample).get(0).path());
        assertEquals("unix:path=/tmp/bus", AtSpi.strings("   string \"unix:path=/tmp/bus\"\n").get(0));
        assertEquals(42, AtSpi.lastInt("variant uint32 42", -1));
        assertEquals("unknown", AtSpi.roleName(22));
        assertEquals("unknown", AtSpi.roleName(42));
        assertEquals("unknown", AtSpi.roleName(78));
        assertNotEquals("button", AtSpi.roleName(42));
        assertNotEquals("radio", AtSpi.roleName(43));
        assertNotEquals("textbox", AtSpi.roleName(39));
        assertNotEquals("menuitem", AtSpi.roleName(34));
        assertEquals("button", AtSpi.roleName(43));
        assertEquals("button", AtSpi.roleName(62));
        assertEquals("radio", AtSpi.roleName(44));
        assertEquals("window", AtSpi.roleName(14));
        assertEquals("window", AtSpi.roleName(23));
        assertEquals("window", AtSpi.roleName(69));
        assertEquals("menu", AtSpi.roleName(33));
        assertEquals("menubar", AtSpi.roleName(34));
        assertEquals("menuitem", AtSpi.roleName(35));
        assertEquals("tab", AtSpi.roleName(37));
        assertEquals("panel", AtSpi.roleName(39));
        assertEquals("terminal", AtSpi.roleName(60));
        assertEquals("textbox", AtSpi.roleName(61));
        assertEquals("application", AtSpi.roleName(75));
        assertEquals("checkbox", AtSpi.roleName(7));
        assertEquals("dialog", AtSpi.roleName(16));
        assertEquals("unknown", AtSpi.roleName(999));

        DomNode root = NativeDesktop.parseLines("""
                NODE\t0\tdesktop\t0\t0\t0\t0\t\tDesktop
                NODE\t1\tButton\t4\t6\t20\t10\t7.1\tSave
                NODE\t1\tAXTextField\t4\t30\t80\t16\t1/2\tTitle
                """, "ax");
        assertEquals("desktop", root.role);
        assertEquals("button", root.children.get(0).role);
        assertEquals("Save", root.children.get(0).accessibleName);
        assertEquals("textbox", root.children.get(1).role);
        assertEquals("1/2", root.children.get(1).attributes.get("ax-path"));

        String winForms = """
                NODE\t0\twindow\t0\t0\t100\t40\t9.1\tNotes
                ATTR\t9.1\tclass-name\tWindowsForms10.Window.8.app.0
                NODE\t1\tpane\t4\t4\t80\t16\t9.2\t
                ATTR\t9.2\tclass-name\tWindowsForms10.EDIT.app.0
                NODE\t1\tpane\t4\t24\t40\t12\t9.3\t
                ATTR\t9.3\tclass-name\tWindowsForms10.BUTTON.app.0
                """;
        DomNode form = NativeDesktop.parseLines(winForms, "uia");
        DesktopAnnotations.apply(form, winForms, "uia-runtime-id");
        assertEquals("window", form.role);
        assertEquals("textbox", form.children.get(0).role);
        assertEquals("TEXTBOX", form.children.get(0).nodeName);
        assertEquals("pane", form.children.get(1).role);

        DomNode window = new DomNode();
        window.role = "window";
        window.accessibleName = "Editor";
        root.children.add(window);
        DomNode picked = NativeDesktop.selectWindow(root, "Edit");
        assertEquals("Editor", picked.children.get(0).accessibleName);
        CdpException missing = assertThrows(CdpException.class, () -> NativeDesktop.selectWindow(root, "Missing"));
        assertTrue(missing.getMessage().contains("not found"), missing.getMessage());
    }

    @Test
    void fixtureStaysInMemoryAndBrowserOnlyStagesFail() throws Exception {
        Path fixture = Files.createTempFile("desktop", ".json");
        Files.writeString(fixture, """
                {"role":"window","name":"Fixture","children":[
                  {"role":"button","name":"Save","x":4,"y":6,"width":20,"height":10}
                ]}
                """);
        CliResult attached = DomEngineMain.dispatch(List.of(
                "desktop", "attach", "--id", "desk", "--fixture", fixture.toString()
        ));
        assertEquals(0, attached.exit(), attached.stderr());
        assertEquals("fixture", Jsons.MAPPER.readTree(attached.stdout()).path("backend").asText());
        assertFalse(Jsons.MAPPER.readTree(attached.stdout()).path("live").asBoolean());

        CliResult clicked = DomEngineMain.dispatch(List.of(
                "desktop", "click", "--id", "desk", "--pipeline", "role=button[name=\"Save\"]"
        ));
        assertEquals(0, clicked.exit(), clicked.stderr());
        assertEquals("fixture", Jsons.MAPPER.readTree(clicked.stdout()).path("backend").asText());

        CliResult pierced = DomEngineMain.dispatch(List.of(
                "desktop", "query", "--id", "desk", "--pipeline", "role=button", "--pierce", "open"
        ));
        assertEquals(1, pierced.exit(), pierced.stderr());
        assertTrue(pierced.stderr().contains("pierce is browser-only"), pierced.stderr());

        CliResult framed = DomEngineMain.dispatch(List.of(
                "desktop", "query", "--id", "desk", "--pipeline", "frame=#child >> css=#framed"
        ));
        assertEquals(1, framed.exit(), framed.stderr());
        assertTrue(framed.stderr().contains("browser-only"), framed.stderr());

        CliResult both = DomEngineMain.dispatch(List.of(
                "desktop", "attach", "--id", "both", "--live", "--fixture", fixture.toString()
        ));
        assertEquals(1, both.exit(), both.stderr());
        assertTrue(both.stderr().contains("not both"), both.stderr());

        CliResult help = DomEngineMain.dispatch(List.of("help"));
        assertTrue(help.stdout().contains("--live"), help.stdout());
        Files.deleteIfExists(fixture);
    }

    @Test
    void accessibilityBusCallUsesSessionAndNotAddress() {
        AtSpi.BusCall role = AtSpi.busCall(
                "/usr/bin/dbus-send",
                "unix:path=/tmp/at-spi/bus_7,guid=abc",
                "org.a11y.atspi.Registry",
                "/org/a11y/atspi/accessible/root",
                AtSpi.GET_ROLE
        );
        assertTrue(role.command().contains("--session"), role.command().toString());
        assertFalse(role.command().stream().anyMatch(part -> part.startsWith("--address=")), role.command().toString());
        assertFalse(role.command().stream().anyMatch(part -> part.startsWith("--bus=")), role.command().toString());
        assertEquals("unix:path=/tmp/at-spi/bus_7,guid=abc", role.env().get("DBUS_SESSION_BUS_ADDRESS"));
        assertEquals(AtSpi.GET_ROLE, role.command().get(role.command().size() - 1));
        assertFalse(AtSpi.GET_ROLE.contains("Properties"));
        assertFalse(AtSpi.GET_ROLE_NAME.contains("Properties"));

        AtSpi.BusCall roleName = AtSpi.busCall(
                "/usr/bin/dbus-send", "unix:path=/tmp/at-spi/bus_7",
                ":1.2", "/org/a11y/atspi/accessible/1", AtSpi.GET_ROLE_NAME
        );
        assertEquals(AtSpi.GET_ROLE_NAME, roleName.command().get(roleName.command().size() - 1));
        assertFalse(roleName.command().contains(AtSpi.PROPERTIES_GET), roleName.command().toString());
        assertFalse(roleName.command().contains("string:Role"), roleName.command().toString());

        AtSpi.BusCall name = AtSpi.busCall(
                "/usr/bin/dbus-send", "unix:path=/tmp/at-spi/bus_7",
                ":1.2", "/org/a11y/atspi/accessible/1",
                AtSpi.PROPERTIES_GET, "string:org.a11y.atspi.Accessible", "string:Name"
        );
        assertTrue(name.command().contains("string:Name"), name.command().toString());
        assertFalse(name.command().contains("string:Role"), name.command().toString());
        assertEquals("window", AtSpi.mappedRole("frame", 43));
        assertEquals("button", AtSpi.mappedRole("button", 39));
        assertEquals("button", AtSpi.mappedRole("", 43));
        assertEquals("panel", AtSpi.mappedRole(null, 39));
    }

    @Test
    void namedFrameAndButtonRoleComeFromGetRoleName() {
        assertEquals("window", NativeDesktop.mapRole("atspi", "frame"));
        assertEquals("window", NativeDesktop.mapRole("atspi", "desktop frame"));
        assertEquals("button", NativeDesktop.mapRole("atspi", "button"));
        assertEquals("panel", NativeDesktop.mapRole("atspi", "panel"));
        assertEquals("terminal", NativeDesktop.mapRole("atspi", "terminal"));
        assertEquals("menu", NativeDesktop.mapRole("atspi", "menu"));
        assertEquals("menubar", NativeDesktop.mapRole("atspi", "menu bar"));
        assertEquals("menuitem", NativeDesktop.mapRole("atspi", "menu item"));
        assertEquals("tab", NativeDesktop.mapRole("atspi", "page tab"));
        assertEquals("application", NativeDesktop.mapRole("atspi", "application"));
        assertEquals("window", AtSpi.mappedRole("desktop frame", 14));

        DomNode root = node("desktop", "Desktop", 1);
        DomNode frame = node(AtSpi.mappedRole("frame", 23), "PickleballLiveTest", 2);
        frame.attributes.put("atspi-role", "23");
        DomNode copy = node(AtSpi.mappedRole("button", 43), "Copy", 3);
        copy.attributes.put("atspi-role", "43");
        DomNode paste = node(AtSpi.mappedRole("button", 43), "Paste", 4);
        DomNode panel = node(AtSpi.mappedRole("panel", 39), "Toolbar", 5);
        frame.children.add(copy);
        frame.children.add(paste);
        frame.children.add(panel);
        root.children.add(frame);

        DomNode picked = NativeDesktop.selectWindow(root, "PickleballLiveTest");
        assertEquals(1, picked.children.size());
        assertEquals("window", picked.children.get(0).role);
        assertEquals("PickleballLiveTest", picked.children.get(0).accessibleName);

        var found = Query.run(picked, Map.of("pipeline", "role=button"));
        assertEquals(2, found.path("nodes").size());
        assertEquals("button", found.path("nodes").get(0).path("role").asText());
        assertEquals("Copy", found.path("composite").get(0).path("name").asText());
        assertEquals("Paste", found.path("composite").get(1).path("name").asText());
        assertEquals(0, Query.run(picked, Map.of("pipeline", "role=radio")).path("nodes").size());
    }

    @Test
    void liveAttachSucceedsWhenAccessibilityBusIsUp() {
        Assumptions.assumeTrue(accessibilityBusIsUp(), "no AT-SPI bus");
        CliResult live = DomEngineMain.dispatch(List.of("desktop", "attach", "--id", "liveok", "--live"));
        assertEquals(0, live.exit(), live.stderr());
        assertTrue(live.stdout().contains("\"ok\":true") || live.stdout().contains("\"ok\": true")
                || live.stdout().contains("\"ok\" : true"), live.stdout());
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void ownedEditTypeReadsBackTheValuePattern() throws Exception {
        Assumptions.assumeTrue("windows".equals(NativeDesktop.family()), "requires Windows");
        String marker = "pkb-p2-live-" + System.nanoTime();
        Process host = startOwnedEdit(marker);
        try {
            DomNode editor = null;
            for (int attempt = 0; attempt < 20 && editor == null; attempt++) {
                try {
                    DesktopTree.close("p2-live-note");
                } catch (RuntimeException ignored) {
                    // The first attempt has no session.
                }
                try {
                    DesktopTree.Session session = DesktopTree.attachLive("p2-live-note", marker);
                    editor = editor(session.root());
                } catch (RuntimeException ignored) {
                    editor = null;
                }
                if (editor == null) Thread.sleep(400);
            }
            assertTrue(editor != null, "owned edit was not on the UIA tree for " + marker);
            String runtimeId = editor.attributes.get("uia-runtime-id");
            assertTrue(runtimeId != null && !runtimeId.isBlank(), "editor has no uia-runtime-id");
            focus(marker);
            WindowsUia.type(editor, "pickleball-p2", false);
            String value = readValue(runtimeId);
            assertEquals("pickleball-p2", value.strip(), value);
        } finally {
            try {
                DesktopTree.close("p2-live-note");
            } catch (RuntimeException ignored) {
                // Already closed.
            }
            closeOwnedEdit(host, marker);
        }
    }

    private static DomNode editor(DomNode node) {
        if (node == null) return null;
        String role = node.role == null ? "" : node.role;
        if ("document".equals(role) || "textbox".equals(role) || "edit".equals(role)) {
            String id = node.attributes.get("uia-runtime-id");
            if (id != null && !id.isBlank()) return node;
        }
        for (DomNode child : node.children) {
            DomNode found = editor(child);
            if (found != null) return found;
        }
        return null;
    }

    private static Process startOwnedEdit(String marker) throws Exception {
        String script = """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                Add-Type -AssemblyName System.Windows.Forms
                Add-Type -AssemblyName System.Drawing
                Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                public class PkbEditChildLive {
                  [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
                  public static extern IntPtr CreateWindowEx(int ex, string cls, string title, int style, int x, int y, int w, int h, IntPtr parent, IntPtr menu, IntPtr inst, IntPtr param);
                  [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
                  public static extern IntPtr GetModuleHandle(string name);
                  public static void Add(IntPtr parent) {
                    IntPtr hwnd = CreateWindowEx(0, "EDIT", "", 0x50800080, 12, 12, 440, 24, parent, new IntPtr(1), GetModuleHandle(null), IntPtr.Zero);
                    if (hwnd == IntPtr.Zero) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
                  }
                }
                '@
                $form = New-Object System.Windows.Forms.Form
                $form.Text = [string]$env:PKB_MARKER
                $form.StartPosition = 'Manual'
                $form.Location = New-Object System.Drawing.Point(180, 180)
                $form.ClientSize = New-Object System.Drawing.Size(480, 120)
                $form.Add_Shown({ [PkbEditChildLive]::Add($form.Handle) })
                [System.Windows.Forms.Application]::Run($form)
                """;
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe", "-STA", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("PKB_MARKER", marker);
        return builder.start();
    }

    private static void focus(String marker) {
        String script = """
                $ErrorActionPreference = 'Stop'
                Add-Type -TypeDefinition @'
                using System;
                using System.Text;
                using System.Runtime.InteropServices;
                public class PkbFocusLive {
                  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
                  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
                  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
                  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
                  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
                  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
                  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
                  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
                  [DllImport("user32.dll")] public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
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
                  public static bool Scan(string want) {
                    Want = want;
                    Found = IntPtr.Zero;
                    callback = Visit;
                    EnumWindows(callback, IntPtr.Zero);
                    return Found != IntPtr.Zero;
                  }
                  public static void Focus(IntPtr hWnd) {
                    uint ignored;
                    uint mine = GetWindowThreadProcessId(hWnd, out ignored);
                    IntPtr fg = GetForegroundWindow();
                    uint fgIgnored;
                    uint fgThread = GetWindowThreadProcessId(fg, out fgIgnored);
                    ShowWindow(hWnd, 9);
                    if (fgThread != mine) AttachThreadInput(fgThread, mine, true);
                    keybd_event(0x12, 0, 0, UIntPtr.Zero);
                    keybd_event(0x12, 0, 2, UIntPtr.Zero);
                    SetForegroundWindow(hWnd);
                    if (fgThread != mine) AttachThreadInput(fgThread, mine, false);
                  }
                }
                '@
                if (-not [PkbFocusLive]::Scan([string]$env:PKB_MARKER)) { exit 2 }
                [PkbFocusLive]::Focus([PkbFocusLive]::Found)
                exit 0
                """;
        NativeDesktop.CommandResult result = runPowershell(script, Map.of("PKB_MARKER", marker));
        assertEquals(0, result.exit(), result.output());
    }

    private static String readValue(String runtimeId) {
        String script = """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                Add-Type -AssemblyName UIAutomationClient
                Add-Type -TypeDefinition @'
                using System;
                using System.Text;
                using System.Runtime.InteropServices;
                public class PkbReadLiveValue {
                  [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "SendMessageW")]
                  public static extern IntPtr SendGet(IntPtr hWnd, uint msg, IntPtr wParam, StringBuilder lParam);
                  public static string Text(IntPtr hwnd) {
                    if (hwnd == IntPtr.Zero) return "";
                    StringBuilder sb = new StringBuilder(4000);
                    SendGet(hwnd, 0x000D, new IntPtr(sb.Capacity), sb);
                    return sb.ToString();
                  }
                }
                '@
                $want = [string]$env:DESKTOP_UIA_ID
                function Find($el) {
                  if ($null -eq $el) { return $null }
                  $id = ''
                  try { $id = (($el.GetRuntimeId() | ForEach-Object { $_.ToString() }) -join '.') } catch {}
                  if ($id -eq $want) { return $el }
                  $kids = @()
                  try { $kids = $el.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition) } catch { return $null }
                  foreach ($kid in $kids) { $hit = Find $kid; if ($null -ne $hit) { return $hit } }
                  return $null
                }
                $el = Find ([System.Windows.Automation.AutomationElement]::RootElement)
                if ($null -eq $el) { Write-Error "missing $want"; exit 1 }
                $text = ''
                try {
                  $pattern = $el.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
                  $text = [string]$pattern.Current.Value
                } catch {}
                if ([string]::IsNullOrEmpty($text)) {
                  $hwnd = [IntPtr]::Zero
                  try { $hwnd = [IntPtr]$el.Current.NativeWindowHandle } catch {}
                  $text = [PkbReadLiveValue]::Text($hwnd)
                }
                if ([string]::IsNullOrEmpty($text)) { Write-Error "empty value for $want"; exit 1 }
                Write-Output $text
                exit 0
                """;
        NativeDesktop.CommandResult result = runPowershell(script, Map.of("DESKTOP_UIA_ID", runtimeId));
        assertEquals(0, result.exit(), result.output());
        return result.output();
    }

    private static NativeDesktop.CommandResult runPowershell(String script, Map<String, String> env) {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return NativeDesktop.run(List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded
        ), env, 25);
    }

    private static void closeOwnedEdit(Process host, String marker) {
        String script = """
                $ErrorActionPreference = 'Stop'
                Add-Type -TypeDefinition @'
                using System;
                using System.Text;
                using System.Runtime.InteropServices;
                public class PkbCloseLive {
                  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
                  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
                  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
                  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr w, IntPtr l);
                  public static string Want;
                  static EnumProc callback;
                  public static bool Visit(IntPtr hWnd, IntPtr lParam) {
                    StringBuilder sb = new StringBuilder(512);
                    GetWindowText(hWnd, sb, sb.Capacity);
                    if (sb.ToString().IndexOf(Want, StringComparison.OrdinalIgnoreCase) >= 0) {
                      PostMessage(hWnd, 0x0010, IntPtr.Zero, IntPtr.Zero);
                    }
                    return true;
                  }
                  public static void CloseAll(string want) {
                    Want = want;
                    callback = Visit;
                    EnumWindows(callback, IntPtr.Zero);
                  }
                }
                '@
                [PkbCloseLive]::CloseAll([string]$env:PKB_MARKER)
                exit 0
                """;
        runPowershell(script, Map.of("PKB_MARKER", marker));
        if (host == null) return;
        host.destroy();
        try {
            if (!host.waitFor(3, TimeUnit.SECONDS)) host.destroyForcibly();
            host.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            host.destroyForcibly();
        }
    }

    private static boolean accessibilityBusIsUp() {
        try {
            AtSpi.busAddress();
            return true;
        } catch (CdpException ignored) {
            return false;
        }
    }

    private static DomNode node(String role, String name, long id) {
        DomNode node = new DomNode();
        node.nodeType = 1;
        node.displayed = true;
        node.frameId = "desktop";
        node.backendNodeId = id;
        node.nodeId = id;
        node.role = role;
        node.localName = role;
        node.nodeName = role.toUpperCase();
        node.accessibleName = name;
        return node;
    }
}
