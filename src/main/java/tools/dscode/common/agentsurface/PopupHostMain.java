package tools.dscode.common.agentsurface;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Out-of-process popup host. One process per project and run id. */
public final class PopupHostMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final long SANDBOX_RETRY_WINDOW_MS = 15_000;

    private PopupHostMain() {
    }

    public static void main(String[] args) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        String runId = "run";
        String ownerKind = "cli";
        long ownerPid = ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L);
        for (String arg : args) {
            if (arg.startsWith("--project=")) project = Path.of(arg.substring("--project=".length()));
            else if (arg.startsWith("--run-id=")) runId = arg.substring("--run-id=".length());
            else if (arg.startsWith("--owner-kind=")) ownerKind = arg.substring("--owner-kind=".length());
            else if (arg.startsWith("--owner-pid=")) ownerPid = Long.parseLong(arg.substring("--owner-pid=".length()));
        }
        redirectOwnOutput(PopupLog.popupDir(project, runId).resolve("host.out"));
        int code;
        try {
            code = serve(project, runId, ownerKind, ownerPid);
        } catch (Throwable failure) {
            failure.printStackTrace();
            code = 1;
        }
        System.out.flush();
        System.err.flush();
        if (code != 0) System.exit(code);
    }

    /**
     * On Windows {@link PopupHost#open} gives the host no output file, and the host opens
     * {@code host.out} itself. A handle the JVM opens is not inheritable, so the browser and its
     * children cannot keep {@code host.out} locked after the host exits.
     */
    private static void redirectOwnOutput(Path hostOut) {
        if (!PopupHost.windows()) return;
        try {
            Files.createDirectories(hostOut.getParent());
            java.io.PrintStream stream = new java.io.PrintStream(
                    new java.io.FileOutputStream(hostOut.toFile(), true), true, java.nio.charset.StandardCharsets.UTF_8);
            System.setOut(stream);
            System.setErr(stream);
        } catch (Exception ignored) {
            // Output stays discarded. The popup log still records the lifecycle.
        }
    }

    public static int serve(Path project, String runId, String ownerKind, long ownerPid) throws Exception {
        Path dir = PopupLog.popupDir(project, runId);
        Files.createDirectories(dir);
        Path profile = dir.resolve("profile");
        Files.createDirectories(profile);
        PopupBrowserLocator.Hit located = PopupBrowserLocator.locate();
        if (located.path().isEmpty()) {
            PopupLog.warn(project, runId, "no-chromium", "no Chromium-family browser was found");
            write(dir.resolve("host.json"), state(project, runId, ownerKind, ownerPid, 0, "", 0, "closed", false, "no-chromium"));
            return 0;
        }
        PopupBridge bridge = PopupBridge.start(project, runId);
        Process browser = null;
        try {
            int[] origin = PopupConfig.origin(PopupConfig.width(), PopupConfig.height());
            boolean noSandbox = PopupConfig.noSandbox();
            Path browserOut = PopupHost.windows() ? null : dir.resolve("browser.out");
            List<String> argv = PopupLauncher.argv(
                    located.path().get(),
                    bridge.baseUrl(),
                    profile,
                    PopupConfig.width(),
                    PopupConfig.height(),
                    origin[0],
                    origin[1],
                    PopupConfig.headless(),
                    noSandbox);
            browser = PopupLauncher.start(argv, browserOut);
            long launchedAt = System.currentTimeMillis();
            PopupOnTop.Attempt onTop = PopupConfig.onTop()
                    ? PopupOnTop.apply(browser.pid())
                    : new PopupOnTop.Attempt(false, "off", "pkb_agent_popup_on_top=false", List.of());
            if (!onTop.applied()) {
                PopupLog.warn(project, runId, "ontop-unavailable", onTop.reason());
            }
            write(dir.resolve("host.json"), state(project, runId, ownerKind, ownerPid, bridge.port(), bridge.token(), browser.pid(), "open", false, onTop));
            long last = System.currentTimeMillis();
            long seen = 0;
            while (true) {
                Thread.sleep(200);
                if (!browser.isAlive() && !noSandbox && System.currentTimeMillis() - launchedAt < SANDBOX_RETRY_WINDOW_MS
                        && PopupLauncher.sandboxFailure(browserOut)) {
                    // Chrome for Testing and other unpackaged builds exit at once when the Linux sandbox
                    // cannot start. Relaunch once without it so the popup still opens.
                    PopupLog.warn(project, runId, "sandbox-unavailable",
                            "browser exited with code " + browser.exitValue() + " because its sandbox could not start; relaunching with --no-sandbox: " + PopupLauncher.sandboxLine(browserOut));
                    noSandbox = true;
                    PopupLauncher.destroyTree(browser);
                    delete(profile);
                    Files.createDirectories(profile);
                    argv = PopupLauncher.argv(located.path().get(), bridge.baseUrl(), profile, PopupConfig.width(), PopupConfig.height(),
                            origin[0], origin[1], PopupConfig.headless(), true);
                    browser = PopupLauncher.start(argv, browserOut);
                    launchedAt = System.currentTimeMillis();
                    write(dir.resolve("host.json"), state(project, runId, ownerKind, ownerPid, bridge.port(), bridge.token(), browser.pid(), "open", false, onTop));
                    continue;
                }
                if (!browser.isAlive()) {
                    write(dir.resolve("host.json"), state(project, runId, ownerKind, ownerPid, bridge.port(), bridge.token(), browser.pid(), "closed-by-user", false, onTop));
                    break;
                }
                if (closed(project, runId)) break;
                if ("run".equals(ownerKind) && ownerPid > 0 && !ProcessHandle.of(ownerPid).isPresent()) break;
                long latest = latest(project, runId);
                if (latest != seen) {
                    seen = latest;
                    last = System.currentTimeMillis();
                }
                if (System.currentTimeMillis() - last >= PopupConfig.idleMillis()) break;
                cap(dir.resolve("host.out"));
                if (browserOut != null) cap(browserOut);
            }
        } finally {
            if (browser != null) PopupLauncher.destroyTree(browser);
            bridge.close();
            delete(profile);
            PopupLog.log(project, runId, "lifecycle", runId, "exit");
        }
        return 0;
    }

    private static boolean closed(Path project, String runId) throws Exception {
        for (PopupLedger.Row row : PopupLedger.read(project, runId)) {
            if ("close".equals(row.op())) return true;
        }
        return false;
    }

    private static long latest(Path project, String runId) throws Exception {
        long max = 0;
        for (PopupLedger.Row row : PopupLedger.read(project, runId)) max = Math.max(max, row.seq());
        return max;
    }

    private static Map<String, Object> state(Path project, String runId, String ownerKind, long ownerPid, int port, String token, long browserPid, String state, boolean reused, String reason) {
        PopupOnTop.Attempt attempt = new PopupOnTop.Attempt(false, "none", reason, List.of());
        return state(project, runId, ownerKind, ownerPid, port, token, browserPid, state, reused, attempt);
    }

    private static Map<String, Object> state(Path project, String runId, String ownerKind, long ownerPid, int port, String token, long browserPid, String state, boolean reused, PopupOnTop.Attempt onTop) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pid", ProcessHandle.current().pid());
        body.put("port", port);
        body.put("token", token);
        Optional<String> browser = PopupBrowserLocator.locate().path();
        body.put("browser", browser.orElse(""));
        body.put("browserPath", browser.orElse(""));
        body.put("browserPid", browserPid);
        body.put("ownerPid", ownerPid);
        body.put("ownerKind", ownerKind);
        body.put("startedAt", Instant.now().toString());
        body.put("headless", PopupConfig.headless());
        body.put("state", state);
        body.put("reused", reused);
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("applied", onTop.applied());
        top.put("method", onTop.method());
        top.put("reason", onTop.reason());
        body.put("alwaysOnTop", top);
        return body;
    }

    private static void write(Path file, Map<String, Object> body) throws Exception {
        Path lock = file.resolveSibling("host.lock");
        Files.createDirectories(file.getParent());
        try (var channel = java.nio.channels.FileChannel.open(lock, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            Files.writeString(file, JSON.writeValueAsString(body));
        }
    }

    private static void cap(Path output) {
        try {
            if (!Files.isRegularFile(output)) return;
            int max = PopupLog.maxBytes();
            if (Files.size(output) <= max) return;
            byte[] bytes = Files.readAllBytes(output);
            int start = Math.max(0, bytes.length - max);
            Files.write(output, java.util.Arrays.copyOfRange(bytes, start, bytes.length));
        } catch (Exception ignored) {
            // A live redirect may still be writing. The next pass retries.
        }
    }

    private static void delete(Path profile) throws Exception {
        if (!Files.exists(profile)) return;
        try (var walk = Files.walk(profile)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // A live browser lock is retried by the next close.
                }
            });
        }
    }
}
