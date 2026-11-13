package tools.dscode.common.agentsurface;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
