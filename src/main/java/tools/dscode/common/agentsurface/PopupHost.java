package tools.dscode.common.agentsurface;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

/** Spawns or reuses the popup host process. */
public final class PopupHost {
    private PopupHost() {
    }

    public static Map<String, Object> open(Path project, String runId) throws Exception {
        Map<String, Object> current = PopupStatus.read(project, runId);
        if (healthy(current)) {
            current.put("reused", true);
            current.put("ok", true);
            PopupLog.log(project, runId, "lifecycle", runId, "reused");
            return current;
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path dir = PopupLog.popupDir(project, runId);
        Files.createDirectories(dir);
        Path argFile = dir.resolve("host.argfile");
        StringBuilder arguments = new StringBuilder();
        arguments.append("-cp\n").append(System.getProperty("java.class.path")).append('\n');
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("pkb_agent_popup")) {
                arguments.append("-D").append(key).append('=').append(System.getProperty(key)).append('\n');
            }
        }
        arguments.append("tools.dscode.common.agentsurface.PopupHostMain\n");
        arguments.append("--project=").append(project.toAbsolutePath()).append('\n');
        arguments.append("--run-id=").append(runId).append('\n');
        arguments.append("--owner-kind=cli\n");
        arguments.append("--owner-pid=").append(ProcessHandle.current().pid()).append('\n');
        Files.writeString(argFile, arguments.toString());
        java.util.List<String> command = new ArrayList<>();
        command.add(java);
        command.add("@" + argFile.toAbsolutePath());
        Path log = PopupLog.popupDir(project, runId).resolve("host.out");
        if (log.getParent() != null) Files.createDirectories(log.getParent());
        ProcessBuilder builder = new ProcessBuilder(command);
        if (windows()) {
            // Windows children inherit every inheritable handle. The host opens host.out itself
            // (not inheritable) so the browser cannot keep it locked after close.
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        } else {
            builder.redirectOutput(log.toFile());
            builder.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        }
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process process = builder.start();
        process.getOutputStream().close();
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        Map<String, Object> status = PopupStatus.read(project, runId);
        while (System.nanoTime() < deadline) {
            status = PopupStatus.read(project, runId);
            if (status.get("port") instanceof Number && ((Number) status.get("port")).intValue() > 0) break;
            Path hostFile = PopupLog.popupDir(project, runId).resolve("host.json");
            Object state = status.get("state");
            if (Files.isRegularFile(hostFile) && ("closed".equals(state) || "closed-by-user".equals(state))) break;
            Thread.sleep(50);
        }
        status.put("reused", false);
        status.put("ok", true);
        return status;
    }

    /**
     * Writes one close row when none is stored, then waits for the host pid, the browser pid,
     * and the profile directory to leave. The bound is about ten seconds.
     */
    public static boolean closeAndWait(Path project, String runId, Duration timeout) throws Exception {
        Duration bound = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(10) : timeout;
        if (!closed(project, runId)) {
            PopupLedger.append(project, runId, "close", "", "", "", "", "", "");
        }
        long deadline = System.nanoTime() + bound.toNanos();
        Path profile = PopupLog.popupDir(project, runId).resolve("profile");
        while (true) {
            if (quiet(project, runId, profile)) return released(project, runId, deadline);
            long now = System.nanoTime();
            if (now >= deadline) break;
            if (deadline - now < Duration.ofSeconds(2).toNanos()) stopHolders(project, runId, profile);
            Thread.sleep(50);
        }
        stopHolders(project, runId, profile);
        deleteTree(profile);
        return quiet(project, runId, profile) && released(project, runId, System.nanoTime() + Duration.ofSeconds(2).toNanos());
    }

    /**
     * On Windows a process that exited can hold its files for a moment. Waits, up to the deadline,
     * until {@code host.out} and {@code browser.out} can be renamed. Other platforms return true.
     */
    private static boolean released(Path project, String runId, long deadline) throws InterruptedException {
        if (!windows()) return true;
        Path dir = PopupLog.popupDir(project, runId);
        java.util.List<Path> files = java.util.List.of(dir.resolve("host.out"), dir.resolve("browser.out"));
        while (true) {
            boolean free = true;
            for (Path file : files) free &= renameable(file);
            if (free) return true;
            if (System.nanoTime() >= deadline) return false;
            Thread.sleep(50);
        }
    }

    private static boolean renameable(Path file) {
        if (!Files.exists(file)) return true;
        Path aside = file.resolveSibling(file.getFileName() + ".release-check");
        try {
            Files.move(file, aside);
            Files.move(aside, file);
            return true;
        } catch (Exception held) {
            return false;
        }
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static boolean closed(Path project, String runId) throws Exception {
        for (PopupLedger.Row row : PopupLedger.read(project, runId)) {
            if ("close".equals(row.op())) return true;
        }
        return false;
    }

    private static boolean quiet(Path project, String runId, Path profile) {
        Map<String, Object> status = PopupStatus.read(project, runId);
        boolean pidsGone = !alive(status.get("pid")) && !alive(status.get("browserPid"));
        if (pidsGone) deleteTree(profile);
        if (Files.exists(profile)) return false;
        // The host logs "exit" before its JVM ends, and that JVM still holds host.out. The log line
        // counts only when the status has no host pid to watch.
        if (status.get("pid") instanceof Number) return pidsGone;
        return pidsGone || loggedExit(project, runId);
    }

    private static boolean loggedExit(Path project, String runId) {
        Path log = PopupLog.popupDir(project, runId).resolve("popup.log");
        if (!Files.isRegularFile(log)) return false;
        try {
            for (String line : Files.readAllLines(log)) {
                if (line.contains(" lifecycle ") && line.endsWith(" exit")) return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static boolean alive(Object value) {
        if (!(value instanceof Number number)) return false;
        long id = number.longValue();
        if (id <= 0) return false;
        var handle = ProcessHandle.of(id);
        if (handle.isEmpty() || !handle.get().isAlive()) return false;
        // A zombie this JVM started is about to be reaped by its own process reaper; wait for that.
        // A zombie whose parent is another process (an orphan the init process has not reaped yet)
        // never leaves on our account, so it counts as gone.
        if (!zombie(id)) return true;
        long self = ProcessHandle.current().pid();
        return handle.get().parent().map(parent -> parent.pid() == self).orElse(false);
    }

    /** A Linux zombie has exited and only waits to be reaped. It holds no files and cannot be killed. */
    static boolean zombie(long pid) {
        Path stat = Path.of("/proc", Long.toString(pid), "stat");
        if (!Files.isRegularFile(stat)) return false;
        try {
            String text = Files.readString(stat);
            int close = text.lastIndexOf(')');
            return close > 0 && close + 2 < text.length() && text.charAt(close + 2) == 'Z';
        } catch (Exception failure) {
            return false;
        }
    }

    private static void stopHolders(Path project, String runId, Path profile) {
        Map<String, Object> status = PopupStatus.read(project, runId);
        kill(status.get("browserPid"));
        kill(status.get("pid"));
        String marker = profile.toAbsolutePath().toString();
        ProcessHandle.allProcesses().forEach(handle -> {
            String command = handle.info().commandLine().orElse("");
            if (!command.isEmpty() && command.contains(marker)) handle.destroyForcibly();
        });
    }

    private static void kill(Object value) {
        if (!(value instanceof Number number)) return;
        long id = number.longValue();
        if (id <= 0) return;
        ProcessHandle.of(id).ifPresent(handle -> {
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            handle.destroyForcibly();
        });
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Chrome can still be releasing the profile. The next pass retries.
                }
            });
        } catch (Exception ignored) {
            // A walk that races a delete is retried by the caller.
        }
    }

    public static boolean healthy(Map<String, Object> status) {
        if (status == null) return false;
        Object pid = status.get("pid");
        Object port = status.get("port");
        Object token = status.get("token");
        if (!(pid instanceof Number) || !(port instanceof Number) || token == null) return false;
        long id = ((Number) pid).longValue();
        if (ProcessHandle.of(id).isEmpty()) return false;
        int usePort = ((Number) port).intValue();
        if (usePort <= 0) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + usePort + "/health?t=" + token))
                    .header("X-Pkb-Popup-Token", String.valueOf(token))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception failure) {
            return false;
        }
    }
}
