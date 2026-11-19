package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Timing fields stamped onto every agent reply. */
public final class ReplyFields {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ReplyFields() {
    }

    /**
     * The CLI round trip is {@code totalMs}. The worker's previous total becomes {@code workerMs}
     * so the printed total is not a copy of the worker's {@code jvmMs}.
     */
    public static String restamp(String bodyJson, long totalMs) {
        long ms = Math.max(0L, totalMs);
        String raw = bodyJson == null || bodyJson.isBlank() ? "{}" : bodyJson;
        try {
            JsonNode node = JSON.readTree(raw);
            if (!node.isObject()) return raw + "\nelapsedMs=" + ms;
            ObjectNode copy = (ObjectNode) node.deepCopy();
            long workerMs = copy.path("timing").path("totalMs").asLong(-1);
            if (workerMs < 0) workerMs = copy.path("elapsedMs").asLong(-1);
            ObjectNode timing = JSON.createObjectNode();
            JsonNode prior = copy.get("timing");
            if (prior != null && prior.isObject()) {
                prior.fields().forEachRemaining(entry -> {
                    if (!"totalMs".equals(entry.getKey())) timing.set(entry.getKey(), entry.getValue());
                });
            }
            if (workerMs >= 0) timing.put("workerMs", workerMs);
            timing.put("totalMs", ms);
            copy.set("timing", timing);
            copy.put("elapsedMs", ms);
            return JSON.writeValueAsString(copy);
        } catch (Exception failure) {
            return raw + "\nelapsedMs=" + ms;
        }
    }

    public static Map<String, Object> stamp(Map<String, Object> body, long elapsedMs) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (body != null) copy.putAll(body);
        long ms = Math.max(0L, elapsedMs);
        copy.put("elapsedMs", ms);
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("totalMs", ms);
        timing.put("jvmMs", ms);
        Object existing = copy.get("timing");
        if (existing instanceof Map<?, ?> prior) {
            prior.forEach((key, value) -> {
                if (key != null && value != null && !"totalMs".equals(key) && !"jvmMs".equals(key)) {
                    timing.put(String.valueOf(key), value);
                }
            });
        }
        copy.put("timing", timing);
        return copy;
    }
}
