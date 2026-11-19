package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.control.protocol.AgentFastLog;
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("op", op == null ? "" : op);
        payload.put("args", args == null ? Map.of() : args);
        payload.put("project", project == null ? "" : project.toAbsolutePath().normalize().toString());
        payload.put("runId", runId == null ? "" : runId);
        payload.put("agentId", agentId == null ? "" : agentId);
        payload.put("extraArgs", extra == null ? List.of() : extra);
        String host = bridge.host() == null || bridge.host().isBlank() ? "127.0.0.1" : bridge.host();
        URI uri = URI.create("http://" + host + ":" + bridge.port() + "/v1/agent/command");
        AgentFastLog.log(project, "forward", "op=" + op + " port=" + bridge.port());
        try {
            byte[] bytes = JSON.writeValueAsBytes(payload);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
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
            JsonNode node = JSON.readTree(text.isBlank() ? "{}" : text);
            int exit = node.path("exit").asInt(1);
            JsonNode body = node.get("body");
            String bodyJson = body == null || body.isNull() ? "{}" : JSON.writeValueAsString(body);
            return new Posted(exit, bodyJson, "");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Posted(1, "", "error: live worker interrupted");
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return new Posted(1, "", "error: live worker unreachable: " + message);
        }
    }
}
