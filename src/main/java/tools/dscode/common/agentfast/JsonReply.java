package tools.dscode.common.agentfast;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Store {@code --json} and domengine JSON become one {@link AgentEnvelope}.
 * The previous document is the single {@code items} entry. {@code command}, {@code count},
 * {@code total}, and {@code rows} stay inside that entry.
 */
public final class JsonReply {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {
    };
    private static final TypeReference<List<Object>> ARRAY = new TypeReference<>() {
    };

    private JsonReply() {
    }

    /** The previous JSON document, or the original text when it is not a JSON object or array. */
    public static String envelope(String value, String op, Path project) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty() || !(trimmed.startsWith("{") || trimmed.startsWith("["))) return value;
        JsonNode node;
        try {
            node = JSON.readTree(trimmed);
        } catch (Exception failure) {
            return value;
        }
        if (!node.isObject() && !node.isArray()) return value;
        if (node.isObject() && node.path("v").asInt(-1) == AgentEnvelope.VERSION && node.has("items") && node.has("op")) {
            return value;
        }
        Object previous = node.isObject() ? JSON.convertValue(node, OBJECT) : JSON.convertValue(node, ARRAY);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(previous));
        boolean failed = node.isObject() && node.has("ok") && node.get("ok").isBoolean() && !node.get("ok").booleanValue();
        if (failed) body.put("ok", false);
        String verb = op == null || op.isBlank() ? "json" : op;
        Map<String, Object> envelope = AgentEnvelope.apply(body, failed ? 1 : 0, verb, project, null);
        try {
            return JSON.writeValueAsString(envelope);
        } catch (Exception failure) {
            return value;
        }
    }

    /**
     * Wraps one print of a JSON document. Text is unchanged. A newline that arrived with the
     * document is kept. {@code println} still adds its own newline when the text had none.
     */
    public static PrintStream wrapping(PrintStream delegate, String op, Path project) {
        PrintStream target = delegate == null ? System.out : delegate;
        return new PrintStream(target, true, StandardCharsets.UTF_8) {
            @Override
            public void print(String value) {
                String rewritten = envelope(value, op, project);
                synchronized (target) {
                    target.print(rewritten);
                    if (value != null && rewritten != value && value.endsWith("\n")
                            && (rewritten == null || !rewritten.endsWith("\n"))) {
                        target.print(value.endsWith("\r\n") ? "\r\n" : "\n");
                    }
                }
            }
        };
    }
}
