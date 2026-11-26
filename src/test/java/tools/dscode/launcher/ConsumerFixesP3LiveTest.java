package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.desktop.NativeDesktop;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P3 Windows live repros. D15 and D16 use a page this test serves.
 * D27, D28, and D29 use a private top-level window this test starts and closes.
 */
class ConsumerFixesP3LiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void cleanup() {
        ConsumerReadyCommands.stopQuiet(temp);
        DesktopTree.resetForTests();
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void stepBadgeReportsItsOwnBoxThenUnstepBadgeRemovesIt() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", exchange -> {
            byte[] body = """
                    <!DOCTYPE html><html><body style="margin:40px">
                    <button id="go" type="button" style="width:80px;height:24px">Go</button>
                    </body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Capture started = run(
                    "start", "--url=http://127.0.0.1:" + port + "/page",
                    "--browser=chrome-headless", "--run-id=p3-badge");
            assertEquals(0, started.code, started.err + started.out);

            JsonNode button = awaitRole();
            String handle = button.path("id").asText();
            assertTrue(handle.matches("s\\d+\\.h\\d+"), button.toString());

            Capture placed = run(
                    "step-badge", "--handle=" + handle, "--number=1", "--text=Go",
                    "--target=browser:auto", "--run-id=p3-badge");
            assertEquals(0, placed.code, placed.err + placed.out);
            JsonNode badge = JSON.readTree(placed.out);
            assertTrue(badge.path("visible").asBoolean(false), placed.out);
            assertFalse(badge.path("lost").asBoolean(false), placed.out);
            assertFalse(badge.path("hidden").asBoolean(false), placed.out);
            assertTrue(badge.path("rect").isObject(), placed.out);
            assertFalse(badge.has("sha256"), placed.out);
            assertFalse(badge.has("guidance"), placed.out);
            String id = badge.path("id").asText();
            assertFalse(id.isBlank(), placed.out);
            assertEquals("yes", eval(
                    "document.querySelector('[data-pkb-id=\"" + id + "\"]') ? 'yes' : 'no'"), placed.out);

            Capture removed = run("unstep-badge", "--handle=" + handle, "--run-id=p3-badge");
            assertEquals(0, removed.code, removed.err + removed.out);
            assertFalse((removed.out + removed.err).contains("unknown handle"), removed.out + removed.err);
            assertEquals("no", eval(
                    "document.querySelector('[data-pkb-id=\"" + id + "\"]') ? 'yes' : 'no'"), removed.out);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void desktopEqualsTargetStaysOnThatWindow() throws Exception {
        assumeTrue("windows".equals(NativeDesktop.family()), "requires Windows");
        String marker = "pkb-p3-" + System.nanoTime();
        Path hostStatus = temp.resolve("owned-edit-status.txt");
        Process host = startOwnedEdit(marker, hostStatus);
        try {
            assertTrue(windowReady(marker), "owned edit window did not appear for " + marker
                    + " exit=" + (host.isAlive() ? "alive" : host.exitValue())
                    + " status=" + hostText(hostStatus)
                    + " probe=" + lastWindowOp);
            Capture bare = run(
                    "act", "--target=desktop:app=" + marker, "--kind=type", "--run-id=p3-d27");
            assertTrue(bare.code != 0, bare.out + bare.err);
            JsonNode bareBody = JSON.readTree(bare.out.isBlank() ? "{}" : bare.out);
            String bareError = bareBody.path("error").asText("");
            assertEquals("act requires --query or --ref", bareError, bare.out + bare.err);
            assertFalse(bareError.contains("query requires"), bareError);
            assertFalse(bareError.contains("--pipeline"), bareError);
            assertFalse(bareError.contains("--selector"), bareError);
            assertFalse(bareError.contains("--text"), bareError);

            assertEquals(0, windowOp(marker, "focus"), "could not focus " + marker);
            Capture snapped = run("snap", "--target=desktop:app=" + marker, "--run-id=p3-d28");
            assertEquals(0, snapped.code, snapped.err + snapped.out);
            assertDesktopFrame(snapped.out, marker);

            Capture found = run(
                    "find", "--target=desktop:app=" + marker, "--query=" + marker, "--run-id=p3-d28");
            assertEquals(0, found.code, found.err + found.out);
            assertDesktopFrame(found.out, marker);

            Capture acted = run(
                    "act", "--target=desktop:app=" + marker, "--kind=click",
                    "--query=" + marker, "--run-id=p3-d29");
            assertEquals(0, acted.code, acted.err + acted.out);
            assertDesktopFrame(acted.out, marker);
            assertFalse(acted.out.contains("devtools"), acted.out);
        } finally {
            closeOwnedEdit(host, marker);
        }
    }

    private static void assertDesktopFrame(String json, String marker) throws Exception {
        JsonNode body = JSON.readTree(json);
        assertEquals("desktop", body.path("kind").asText(), json);
        String stateFrame = body.path("state").path("frame").asText();
        assertTrue(stateFrame.startsWith("desktop:"), json);
        assertTrue(stateFrame.contains(marker) || body.path("frame").asText().contains(marker), json);
        assertFalse(body.path("frame").asText().contains("devtools"), json);
    }

    private JsonNode awaitRole() throws Exception {
        JsonNode last = JSON.createObjectNode();
        for (int attempt = 0; attempt < 40; attempt++) {
            Capture found = run(
                    "find", "--target=browser:auto", "--role=button", "--name=Go", "--run-id=p3-badge");
            if (found.code == 0 && !found.out.isBlank()) {
                last = JSON.readTree(found.out);
                JsonNode items = last.path("items");
                if (items.size() > 0 && "Go".equals(items.get(0).path("name").asText())) return items.get(0);
            }
            Thread.sleep(250);
        }
        throw new AssertionError("missing button Go " + last);
    }

    private String eval(String expression) throws Exception {
        Capture read = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=p3-badge", "--expr=" + expression);
        assertEquals(0, read.code, read.err + read.out);
        return JSON.readTree(read.out).path("value").asText("");
    }

    private Capture run(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static String lastWindowOp = "";

    private boolean windowReady(String marker) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (windowOp(marker, "probe") == 0) return true;
            Thread.sleep(400);
        }
        return false;
    }

    private static String hostText(Path hostStatus) {
        try {
            if (hostStatus == null || !Files.exists(hostStatus)) return "";
            String text = Files.readString(hostStatus);
            return text.length() > 500 ? text.substring(0, 500) : text;
        } catch (Exception failure) {
            return failure.getMessage();
        }
    }

    private static Process startOwnedEdit(String marker, Path hostStatus) throws Exception {
        String script = """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                Add-Type -AssemblyName System.Windows.Forms
                Add-Type -AssemblyName System.Drawing
                Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                public class PkbEditChild3 {
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
                $form.Add_Shown({ [PkbEditChild3]::Add($form.Handle) })
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
                public class PkbWin3 {
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
                if (-not [PkbWin3]::Scan([string]$env:PKB_MARKER)) { exit 2 }
                if ($env:PKB_WIN -eq 'probe') { exit 0 }
                if ($env:PKB_WIN -eq 'close') { [PkbWin3]::PostMessage([PkbWin3]::Found, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null; exit 0 }
                [PkbWin3]::Focus([PkbWin3]::Found)
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
            ProcessBuilder builder = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
            builder.redirectErrorStream(true);
            builder.environment().putAll(env);
            Process process = builder.start();
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

    private record Capture(int code, String out, String err) {
    }
}
