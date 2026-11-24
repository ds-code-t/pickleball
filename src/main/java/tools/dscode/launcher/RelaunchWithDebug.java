package tools.dscode.launcher;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code relaunch-with-debug --browser=chrome|msedge [--port=9222] --confirm} (D13 route 4, last resort). Closes the
 * current user's running browser of that kind (a normal close request, not a kill, and only processes owned by this
 * user) and starts it again with the same default profile plus {@code --remote-debugging-port}. In-memory state of
 * unsaved tabs and forms is lost. It never runs without {@code --confirm}.
 */
final class RelaunchWithDebug {
    static final String WARNING = "relaunch closes the browser and reopens it with the same profile and a debugging port; in-memory state of unsaved tabs and forms is lost";

    private RelaunchWithDebug() {
    }

    static int run(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        String[] args = parsed.forwarded();
        String browser = Optional.ofNullable(SetupCommand.value(args, "--browser")).orElse("chrome").trim().toLowerCase(Locale.ROOT);
        if (!List.of("chrome", "msedge").contains(browser)) {
            err.println(WorkbenchCliHelp.structured("relaunch-with-debug supports --browser=chrome or msedge",
                    "Firefox attaches over WebDriver BiDi when started with its remote agent; WebKit has no generic attach").stripTrailing());
            return 2;
        }
        int port;
        try {
            port = Integer.parseInt(Optional.ofNullable(SetupCommand.value(args, "--port")).orElse("9222").trim());
        } catch (NumberFormatException failure) {
            port = -1;
        }
        if (port < 1 || port > 65535) {
            err.println(WorkbenchCliHelp.structured("--port must be 1-65535", "relaunch-with-debug --browser=" + browser + " --port=9222 --confirm").stripTrailing());
            return 2;
        }
        String binary = "msedge".equals(browser) ? dev.domengine.session.BrowserSession.findEdge() : dev.domengine.session.BrowserSession.findChrome();
        Path profile = BrowserAttach.userDataDir(browser, BrowserAttach.probes);
        ObjectNode plan = BrowserAttach.JSON.createObjectNode().put("op", "relaunch-with-debug").put("browser", browser)
                .put("binary", binary == null ? "" : binary).put("profile", String.valueOf(profile)).put("port", port).put("warning", WARNING);
        if (!SetupCommand.confirmed(args)) {
            err.println(WorkbenchCliHelp.structured("relaunch-with-debug needs --confirm: " + WARNING,
                    "prefer attach --cdp-endpoint=" + browser + " after enabling chrome://inspect/#remote-debugging; to relaunch anyway run relaunch-with-debug --browser=" + browser + " --confirm").stripTrailing());
            out.println(plan.put("ok", false).put("relaunched", false));
            return 2;
        }
        if (binary == null) {
            err.println(WorkbenchCliHelp.structured("no " + browser + " binary found", "install " + browser + " or set DOMENGINE_CHROME / DOMENGINE_EDGE").stripTrailing());
            return 1;
        }
        BrowserAttach.log(parsed.project(), "relaunch:" + browser, "http://127.0.0.1:" + port);
        try {
            List<ProcessHandle> running = ownBrowserProcesses(binary);
            for (ProcessHandle handle : running) handle.destroy();
            long deadline = System.currentTimeMillis() + 10_000;
            for (ProcessHandle handle : running) {
                long left = Math.max(0, deadline - System.currentTimeMillis());
                handle.onExit().get(left, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            new ProcessBuilder(binary, "--remote-debugging-port=" + port, "--restore-last-session")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            String ws = null;
            long until = System.currentTimeMillis() + 20_000;
            while (ws == null && System.currentTimeMillis() < until) {
                ws = BrowserAttach.LIVE.jsonVersion("http://127.0.0.1:" + port);
                if (ws == null) Thread.sleep(250);
            }
            plan.put("closed", running.size());
            if (ws == null) {
                err.println(WorkbenchCliHelp.structured(browser + " restarted but no DevTools endpoint on 127.0.0.1:" + port,
                        "Chrome 136 and later ignore --remote-debugging-port on the default profile: enable chrome://inspect/#remote-debugging and run attach --cdp-endpoint=" + browser).stripTrailing());
                out.println(plan.put("ok", false).put("relaunched", true));
                return 1;
            }
            out.println(plan.put("ok", true).put("relaunched", true).put("endpoint", "http://127.0.0.1:" + port)
                    .put("next", "attach --cdp-endpoint=http://127.0.0.1:" + port));
            return 0;
        } catch (IOException | InterruptedException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            err.println(WorkbenchCliHelp.structured("relaunch-with-debug failed: " + failure.getMessage(), "close the browser yourself and start it with --remote-debugging-port=" + port).stripTrailing());
            return 1;
        }
    }

    /** Top-level processes of that binary owned by the current OS user; nothing else is touched. */
    static List<ProcessHandle> ownBrowserProcesses(String binary) throws IOException {
        Path real = Path.of(binary).toRealPath();
        String user = System.getProperty("user.name");
        List<ProcessHandle> out = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(handle -> {
            ProcessHandle.Info info = handle.info();
            if (info.user().map(owner -> !owner.equals(user) && !owner.endsWith("\\" + user)).orElse(true)) return;
            Optional<String> command = info.command();
            if (command.isEmpty()) return;
            try {
                if (!Files.exists(Path.of(command.get())) || !Path.of(command.get()).toRealPath().equals(real)) return;
            } catch (IOException | RuntimeException failure) {
                return;
            }
            boolean child = handle.parent().flatMap(parent -> parent.info().command()).map(parent -> parent.equals(command.get())).orElse(false);
            if (!child) out.add(handle);
        });
        return out;
    }
}
