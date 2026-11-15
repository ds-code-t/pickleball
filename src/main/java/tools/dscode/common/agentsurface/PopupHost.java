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
        builder.redirectOutput(log.toFile());
        builder.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
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
            if (quiet(project, runId, profile)) return true;
            long now = System.nanoTime();
            if (now >= deadline) break;
            if (deadline - now < Duration.ofSeconds(2).toNanos()) stopHolders(project, runId, profile);
            Thread.sleep(50);
        }
        stopHolders(project, runId, profile);
        deleteTree(profile);
        return quiet(project, runId, profile);
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
        if (!Files.exists(profile) && (pidsGone || loggedExit(project, runId))) return true;
        return false;
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
        return id > 0 && ProcessHandle.of(id).isPresent();
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
