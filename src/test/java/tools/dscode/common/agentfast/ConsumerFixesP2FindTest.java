package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.desktop.NativeDesktop;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.launcher.WorkbenchAgentCommands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** P2: same-origin frames, budget-ms, desktop type targets, and the foreground guard. */
class ConsumerFixesP2FindTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void resetDesktop() {
        DesktopTree.resetForTests();
    }

    @Test
    void sameOriginNormalizesDefaultPortsAndTheTopFrameStaysSameOrigin() {
        assertFalse(TargetVerb.sameOrigin("http://example.com:81/a", "http://example.com/a"));
        assertFalse(TargetVerb.sameOrigin("http://example.com:8080/a", "http://example.com:9090/b"));
        assertTrue(TargetVerb.sameOrigin("http://example.com/a", "http://example.com:80/b"));
        assertTrue(TargetVerb.sameOrigin("https://example.com/a", "https://example.com:443/b"));
        assertFalse(TargetVerb.sameOrigin("about:blank", "http://example.com/"));

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("id", "top");
        top.put("parentId", "");
        top.put("url", "about:blank");
        rows.add(top);
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("id", "child");
        child.put("parentId", "top");
        child.put("url", "https://other.example/x");
        rows.add(child);
        Map<String, Object> blankChild = new LinkedHashMap<>();
        blankChild.put("id", "blank");
        blankChild.put("parentId", "top");
        blankChild.put("url", "about:blank");
        rows.add(blankChild);
        TargetVerb.stampSameOrigin(rows);
        assertEquals(Boolean.TRUE, top.get("sameOrigin"));
        assertEquals(Boolean.FALSE, child.get("sameOrigin"));
        assertEquals(Boolean.FALSE, blankChild.get("sameOrigin"));
    }

    @Test
    void titlesMatchWhenOneContainsTheOtherAndBlankNeverMatches() {
        assertTrue(TargetVerb.titlesMatch("Notepad", "Untitled - Notepad"));
        assertTrue(TargetVerb.titlesMatch("Untitled - Notepad", "Notepad"));
        assertFalse(TargetVerb.titlesMatch("", "Notepad"));
        assertFalse(TargetVerb.titlesMatch("Notepad", " "));
        assertFalse(TargetVerb.titlesMatch("Calculator", "Untitled - Notepad"));
    }

    @Test
    void hyphenIsASpaceAndTypeKeepsTheDocument() {
        assertEquals("upload file", TargetVerb.wordsOf("Upload-File"));
        assertTrue(TargetVerb.wordRun("Upload File", TargetVerb.wordsOf("Upload-File")));
        assertFalse(TargetVerb.wordRun("Upload File", TargetVerb.wordsOf("Uplo")));

        DomNode document = node("document", "Notes");
        DomNode button = node("button", "Notes");
        document.children.add(button);
        List<DomNode> typed = TargetVerb.locateQuery(document, "Notes", Map.of("text-target", "true"));
        assertEquals(1, typed.size(), typed.toString());
        assertEquals("document", typed.get(0).role);
        List<DomNode> clicked = TargetVerb.locateQuery(document, "Notes", Map.of());
        assertEquals(1, clicked.size(), clicked.toString());
        assertEquals("button", clicked.get(0).role);
    }

    @Test
    void typeIntoANamedWindowUsesTheEditorInsideIt() {
        DomNode window = node("window", "pkb-p2-note.txt - Notepad");
        DomNode document = node("document", "Text Editor");
        DomNode textbox = node("textbox", "");
        window.children.add(document);
        document.children.add(textbox);
        window.children.add(node("button", "Close"));
        List<DomNode> editors = TargetVerb.typeEditors(List.of(window));
        assertEquals(2, editors.size(), editors.toString());
        assertEquals("document", editors.get(0).role);
        assertEquals("textbox", editors.get(1).role);
        List<DomNode> already = TargetVerb.typeEditors(List.of(document));
        assertEquals(1, already.size());
        assertEquals("document", already.get(0).role);
    }

    @Test
    void budgetMsZeroKeepsTheHitAndALongBudgetAddsNoField() throws Exception {
        Path fixture = notes();
        Capture zero = agent("find", "--target=desktop:app~Notes", "--fixture=" + fixture,
                "--query=Save", "--budget-ms=0", "--run-id=p2-budget-0");
        assertEquals(0, zero.code, zero.err + zero.out);
        JsonNode hit = JSON.readTree(zero.out);
        assertEquals("Save", hit.path("items").get(0).path("name").asText(), zero.out);
        assertTrue(hit.path("budgetExceeded").asBoolean(), zero.out);

        DesktopTree.resetForTests();
        Capture room = agent("find", "--target=desktop:app~Notes", "--fixture=" + fixture,
                "--query=Save", "--budget-ms=60000", "--run-id=p2-budget-long");
        assertEquals(0, room.code, room.err + room.out);
        assertFalse(room.out.contains("budgetExceeded"), room.out);

        DesktopTree.resetForTests();
        Capture acted = agent("act", "--target=desktop:app~Notes", "--fixture=" + fixture,
                "--kind=click", "--query=Save", "--run-id=p2-budget-act");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode act = JSON.readTree(acted.out);
        assertEquals("Notes", act.path("window").asText(), acted.out);
        assertFalse(acted.out.contains("desktop input refused"), acted.out);

        Capture bad = agent("find", "--target=desktop:app~Notes", "--fixture=" + fixture,
                "--query=Save", "--budget-ms=nope", "--run-id=p2-budget-bad");
        assertEquals(1, bad.code, bad.err + bad.out);
        assertTrue((bad.out + bad.err).contains("budget-ms is milliseconds"), bad.out + bad.err);
    }

    @Test
    void lookupProgressPrintsAfterTwoSeconds() throws Exception {
        TargetVerb.LookupBudget budget = TargetVerb.LookupBudget.parse(Map.of("budget-ms", "10000"));
        ByteArrayOutputStream early = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(early, true, StandardCharsets.UTF_8));
        try {
            budget.progress("find");
        } finally {
            System.setErr(previous);
        }
        assertFalse(early.toString(StandardCharsets.UTF_8).contains("still looking"), early.toString(StandardCharsets.UTF_8));

        Thread.sleep(2100);
        ByteArrayOutputStream later = new ByteArrayOutputStream();
        System.setErr(new PrintStream(later, true, StandardCharsets.UTF_8));
        try {
            budget.progress("find");
        } finally {
            System.setErr(previous);
        }
        String line = later.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains("find still looking"), line);
        assertTrue(line.contains(" ms"), line);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void minimizedWindowRefusesTypeUntilItIsForeground() throws Exception {
        assumeTrue("windows".equals(NativeDesktop.family()), "requires Windows");
        String marker = "pkb-p2-" + System.nanoTime();
        Path hostErr = temp.resolve("owned-edit-err.txt");
        Path hostStatus = temp.resolve("owned-edit-status.txt");
        Process host = startOwnedEdit(marker, hostErr, hostStatus);
        try {
            assertTrue(windowReady(marker), "owned edit window did not appear for " + marker
                    + " exit=" + (host.isAlive() ? "alive" : host.exitValue())
                    + " status=" + hostText(hostStatus)
                    + " probe=" + lastWindowOp
                    + " find=" + lastFind
                    + " err=" + hostText(hostErr));
            assertEquals(0, windowOp(marker, "min"), "could not minimize " + marker);
            String secret = "pkb-refused-secret";
            Capture refused = agent("act", "--target=desktop:app~" + marker, "--kind=type",
                    "--query=" + marker, "--value=" + secret, "--run-id=p2-fg-refuse");
            assertTrue(refused.code != 0, refused.out + refused.err);
            assertTrue((refused.out + refused.err).contains("desktop input refused"), refused.out + refused.err);
            assertTrue((refused.out + refused.err).contains(marker), refused.out + refused.err);
            assertFalse(readEditor(marker).contains(secret), "a background window was typed into");

            assertEquals(0, windowOp(marker, "focus"), "could not focus " + marker);
            String typed = "pkb-typed-ok";
            Capture wrote = agent("act", "--target=desktop:app~" + marker, "--kind=type",
                    "--query=" + marker, "--pick=document", "--value=" + typed, "--run-id=p2-fg-type");
            if (wrote.code != 0) {
                DesktopTree.resetForTests();
                wrote = agent("act", "--target=desktop:app~" + marker, "--kind=type",
                        "--query=" + marker, "--pick=textbox", "--value=" + typed, "--run-id=p2-fg-type2");
            }
            assertEquals(0, wrote.code, wrote.err + wrote.out + " tree=" + treeSummary(marker));
            assertFalse(wrote.out.contains("desktop input refused"), wrote.out);
            assertTrue(wrote.out.contains("\"window\""), wrote.out);
            String value = readEditor(marker);
            assertTrue(value.contains(typed), "read-back was " + value + " from " + wrote.out);
        } finally {
            closeOwnedEdit(host, marker);
        }
    }

    private Path notes() throws Exception {
        Path fixture = temp.resolve("notes.json");
        Files.writeString(fixture, """
                {"role":"window","name":"Notes","x":0,"y":0,"width":200,"height":100,"children":[
                  {"role":"button","name":"Save","x":10,"y":10,"width":40,"height":20}
                ]}
                """);
        return fixture;
    }

    private static String treeSummary(String marker) {
        try {
            DesktopTree.resetForTests();
            DesktopTree.Session session = DesktopTree.attachLive("p2-fg-tree", marker);
            StringBuilder text = new StringBuilder();
            summarize(session.root(), text, 0);
            String summary = text.toString();
            return summary.length() > 500 ? summary.substring(0, 500) : summary;
        } catch (RuntimeException failure) {
            return failure.getMessage() == null ? failure.toString() : failure.getMessage();
        }
    }

    private static void summarize(DomNode node, StringBuilder text, int depth) {
        if (node == null || depth > 4 || text.length() > 500) return;
        text.append(node.role).append('/').append(node.attributes.getOrDefault("class-name", "")).append(':')
                .append(node.accessibleName).append(' ');
        for (DomNode child : node.children) summarize(child, text, depth + 1);
    }

    private static String lastWindowOp = "";
    private String lastFind = "";

    private boolean windowReady(String marker) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (windowOp(marker, "probe") == 0) {
                try {
                    Capture found = agent("find", "--target=desktop:app~" + marker, "--query=" + marker, "--run-id=p2-fg-see");
                    String detail = found.code + " " + found.out + found.err;
                    lastFind = detail.length() > 500 ? detail.substring(0, 500) : detail;
                    if (found.code == 0 && found.out.contains(marker)) return true;
                } catch (RuntimeException failure) {
                    lastFind = failure.getMessage() == null ? failure.toString() : failure.getMessage();
                }
                DesktopTree.resetForTests();
            }
            Thread.sleep(400);
        }
        return false;
    }

    private String readEditor(String marker) {
        String script = """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                Add-Type -TypeDefinition @'
                using System;
                using System.Text;
                using System.Collections.Generic;
                using System.Runtime.InteropServices;
                public class PkbReadEdit {
                  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
                  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
                  [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr parent, EnumProc cb, IntPtr lParam);
                  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
                  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr hWnd, StringBuilder text, int count);
                  [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "SendMessageW")]
                  public static extern IntPtr SendGet(IntPtr hWnd, uint msg, IntPtr wParam, StringBuilder lParam);
                  public static IntPtr Found;
                  public static string Want;
                  static EnumProc top;
                  static EnumProc child;
                  public static List<IntPtr> Kids = new List<IntPtr>();
                  public static bool Top(IntPtr hWnd, IntPtr lParam) {
                    StringBuilder sb = new StringBuilder(512);
                    GetWindowText(hWnd, sb, sb.Capacity);
                    if (Want != null && sb.ToString().IndexOf(Want, StringComparison.OrdinalIgnoreCase) >= 0) { Found = hWnd; return false; }
                    return true;
                  }
                  public static bool Child(IntPtr hWnd, IntPtr lParam) { Kids.Add(hWnd); return true; }
                  public static IntPtr Window(string want) {
                    Want = want; Found = IntPtr.Zero; top = Top;
                    EnumWindows(top, IntPtr.Zero);
                    return Found;
                  }
                  public static string EditText(IntPtr parent) {
                    Kids = new List<IntPtr>();
                    child = Child;
                    EnumChildWindows(parent, child, IntPtr.Zero);
                    foreach (IntPtr kid in Kids) {
                      StringBuilder cls = new StringBuilder(256);
                      GetClassName(kid, cls, cls.Capacity);
                      if (cls.ToString().IndexOf("EDIT", StringComparison.OrdinalIgnoreCase) < 0) continue;
                      StringBuilder sb = new StringBuilder(4000);
                      SendGet(kid, 0x000D, new IntPtr(sb.Capacity), sb);
                      return sb.ToString();
                    }
                    return "";
                  }
                }
                '@
                $win = [PkbReadEdit]::Window([string]$env:PKB_MARKER)
                if ($win -eq [IntPtr]::Zero) { Write-Output ''; exit 0 }
                Write-Output ([PkbReadEdit]::EditText($win))
                exit 0
                """;
        return powershell(script, Map.of("PKB_MARKER", marker)).strip();
    }

    /**
     * A private top-level window with one EDIT control. Win11 Notepad is a single-instance packaged app, so
     * opening a file can attach to a Notepad the user already has open. This host is the only process the test closes.
     */
    private static String hostText(Path hostErr) {
        try {
            if (hostErr == null || !Files.exists(hostErr)) return "";
            String text = Files.readString(hostErr);
            return text.length() > 500 ? text.substring(0, 500) : text;
        } catch (Exception failure) {
            return failure.getMessage();
        }
    }

    private static Process startOwnedEdit(String marker, Path hostErr, Path hostStatus) throws Exception {
        String script = """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                Add-Type -AssemblyName System.Windows.Forms
                Add-Type -AssemblyName System.Drawing
                Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                public class PkbEditChild {
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
                $form.Add_Shown({ [PkbEditChild]::Add($form.Handle) })
                Set-Content -LiteralPath $env:PKB_STATUS -Value ('starting ' + $form.Text)
                [System.Windows.Forms.Application]::Run($form)
                """;
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe", "-STA", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("PKB_MARKER", marker);
        builder.environment().put("PKB_STATUS", hostStatus.toAbsolutePath().toString());
        return builder.start();
    }

    private static int windowOp(String marker, String op) {
        String script = """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                Add-Type -TypeDefinition @'
                using System;
                using System.Text;
                using System.Runtime.InteropServices;
                public class PkbWin {
                  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
                  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
                  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
                  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
                  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
                  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
                  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
                  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
                  [DllImport("user32.dll")] public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
                  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
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
                if (-not [PkbWin]::Scan([string]$env:PKB_MARKER)) { exit 2 }
                if ($env:PKB_WIN -eq 'probe') { exit 0 }
                if ($env:PKB_WIN -eq 'min') { [PkbWin]::ShowWindow([PkbWin]::Found, 11) | Out-Null; exit 0 }
                if ($env:PKB_WIN -eq 'close') { [PkbWin]::PostMessage([PkbWin]::Found, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null; exit 0 }
                [PkbWin]::Focus([PkbWin]::Found)
                exit 0
                """;
        OpResult result = powershellResult(script, Map.of("PKB_MARKER", marker, "PKB_WIN", op));
        lastWindowOp = result.output.length() > 400 ? result.output.substring(0, 400) : result.output;
        return result.code;
    }

    private record OpResult(int code, String output) {
    }

    private static OpResult powershellResult(String script, Map<String, String> env) {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        try {
            Process process = powershellProcess(encoded, env);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new OpResult(1, output + " timeout");
            }
            return new OpResult(process.exitValue(), output);
        } catch (Exception failure) {
            return new OpResult(1, failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
    }

    private static String powershell(String script, Map<String, String> env) {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        try {
            Process process = powershellProcess(encoded, env);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(25, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return process.exitValue() == 0 ? output : "";
        } catch (Exception failure) {
            return "";
        }
    }

    private static int powershellExit(String script, Map<String, String> env) {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        try {
            Process process = powershellProcess(encoded, env);
            process.getInputStream().readAllBytes();
            if (!process.waitFor(25, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return 1;
            }
            return process.exitValue();
        } catch (Exception failure) {
            return 1;
        }
    }

    private static Process powershellProcess(String encoded, Map<String, String> env) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
        builder.redirectErrorStream(true);
        builder.environment().putAll(env);
        return builder.start();
    }

    private static void closeOwnedEdit(Process host, String marker) {
        try {
            windowOp(marker, "close");
        } catch (RuntimeException ignored) {
            // Destroy still ends the host this test started.
        }
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

    private Capture agent(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static DomNode node(String role, String name) {
        DomNode node = new DomNode();
        node.nodeType = 1;
        node.displayed = true;
        node.role = role;
        node.localName = role;
        node.nodeName = role.toUpperCase(java.util.Locale.ROOT);
        node.accessibleName = name;
        return node;
    }

    private record Capture(int code, String out, String err) {
    }
}
