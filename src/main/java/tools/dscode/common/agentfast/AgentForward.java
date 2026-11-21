package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.control.protocol.AgentFastLog;
import tools.dscode.control.protocol.ClientHeartbeat;
import tools.dscode.control.protocol.LiveBridgeFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Posts one agent verb to the live worker {@code POST /v1/agent/command}.
 * A failed post is the result. Callers must not fall through to a local driver.
 */
public final class AgentForward {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private AgentForward() {
    }

    public record Posted(int exit, String bodyJson, String error) { }

    public static Posted post(
            LiveBridgeFile.Bridge bridge,
            String op,
            Map<String, String> args,
            List<String> extra,
            Path project,
            String runId,
            String agentId
    ) {
        String host = bridge.host() == null || bridge.host().isBlank() ? "127.0.0.1" : bridge.host();
        URI uri = URI.create("http://" + host + ":" + bridge.port() + "/v1/agent/command");
        AgentFastLog.log(project, "forward", "op=" + op + " port=" + bridge.port());
        return post(bridge, op, args, extra, project, runId, agentId, httpTimeout(op, args), uri);
    }

    /**
     * The HTTP budget must outlive the verb. A flat 60s made {@code chat-turn --timeout=120}
     * look like the worker was gone after the message had already been sent.
     */
    public static Duration httpTimeout(String op, Map<String, String> args) {
        long declared = 45;
        if (waits(op)) {
            declared = 30;
            String raw = args == null ? null : args.get("timeout");
            if (raw != null && !raw.isBlank()) {
                try {
                    declared = Long.parseLong(raw.trim());
                } catch (NumberFormatException ignored) {
                    declared = 30;
                }
            }
            if (declared < 0) declared = 0;
            if (declared > 120) declared = 120;
        }
        return Duration.ofSeconds(Math.min(180L, Math.max(60L, declared + 15L)));
    }

    public static Posted post(
            LiveBridgeFile.Bridge bridge,
            String op,
            Map<String, String> args,
            List<String> extra,
            Path project,
            String runId,
            String agentId,
            Duration timeout
    ) {
        String host = bridge.host() == null || bridge.host().isBlank() ? "127.0.0.1" : bridge.host();
        URI uri = URI.create("http://" + host + ":" + bridge.port() + "/v1/agent/command");
        return post(bridge, op, args, extra, project, runId, agentId, timeout, uri);
    }

    private static Posted post(
            LiveBridgeFile.Bridge bridge,
            String op,
            Map<String, String> args,
            List<String> extra,
            Path project,
            String runId,
            String agentId,
            Duration timeout,
            URI uri
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("op", op == null ? "" : op);
        payload.put("args", args == null ? Map.of() : args);
        payload.put("project", project == null ? "" : project.toAbsolutePath().normalize().toString());
        payload.put("runId", runId == null ? "" : runId);
        payload.put("agentId", agentId == null ? "" : agentId);
        payload.put("extraArgs", extra == null ? List.of() : extra);
        try {
            byte[] bytes = JSON.writeValueAsBytes(payload);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout == null ? Duration.ofSeconds(60) : timeout)
                    .header("Authorization", "Bearer " + bridge.token())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String text = new String(response.body(), StandardCharsets.UTF_8);
            if (response.statusCode() != 200) {
                return new Posted(1, "", "error: live worker HTTP " + response.statusCode());
            }
            ClientHeartbeat.touch(project);
            JsonNode node = JSON.readTree(text.isBlank() ? "{}" : text);
            int exit = node.path("exit").asInt(1);
            JsonNode body = node.get("body");
            String bodyJson = body == null || body.isNull() ? "{}" : JSON.writeValueAsString(body);
            return new Posted(exit, bodyJson, "");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Posted(1, "", "error: live worker interrupted");
        } catch (Exception failure) {
            return failed(bridge, op, failure);
        }
    }

    /** A live pid is a timeout. Unreachable is only a worker that is actually gone. */
    static Posted failed(LiveBridgeFile.Bridge bridge, String op, Exception failure) {
        boolean alive = bridge != null && bridge.pid() > 0
                && ProcessHandle.of(bridge.pid()).map(ProcessHandle::isAlive).orElse(false);
        if (alive && waits(op)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("op", op);
            body.put("error", "timeout");
            body.put("sentence", "timed out waiting for the live worker");
            body.put("fix", "chat-send --source=human, then chat-turn again");
            try {
                return new Posted(1, JSON.writeValueAsString(body), "");
            } catch (java.io.IOException ignored) {
                return new Posted(1, "{\"ok\":false,\"error\":\"timeout\",\"op\":\"" + op + "\"}", "");
            }
        }
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return new Posted(1, "", "error: live worker unreachable: " + message);
    }

    private static boolean waits(String op) {
        return "chat-turn".equals(op) || "chat_wait".equals(op) || "ask-and-wait".equals(op)
                || "watch".equals(op) || "wait".equals(op) || "wait-for".equals(op);
    }
}
