package tools.dscode.workbench.mcp.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.workbench.WorkbenchServices;
import tools.dscode.workbench.mcp.McpCommandContributor;
import tools.dscode.workbench.mcp.popup.PopupMcpCommands;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed page-UI tools: {@code ui} (a batch of ops, see {@code ui_catalog}), {@code ui_poll} (events
 * and chat in one long-poll), and {@code ui_catalog}. Each forwards to the agent spine
 * ({@code ui-apply}, {@code ui-poll}, {@code ui-catalog}).
 */
public final class WorkbenchUiTools implements McpCommandContributor {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> COMPONENTS = List.of("overlay", "label", "border", "arrow", "step-badge", "magnifier", "spotlight",
            "panel", "panel-close", "guide", "guide-next", "guide-back", "guide-done", "ask", "chat", "remove", "clear");

    @Override
    public void contribute(Sink sink) {
        sink.register("ui",
                "Show page UI in one call: callouts, labels, panel, numbered guides that advance as the person acts, questions. Options: ui_catalog. Results: ui_poll.",
                uiSchema(), arguments -> forward("ui-apply", arguments));
        sink.register("ui_poll",
                "Wait for what the person did: step, guide, answer and click events plus chat, merged. Pass next as after and chatNext as chatAfter.",
                pollSchema(), arguments -> forward("ui-poll", arguments));
        sink.register("ui_catalog",
                "List page UI components for the ui tool: options with defaults, a one-line example op, and how to undo each.",
                catalogSchema(), arguments -> forward("ui-catalog", arguments));
    }

    static Object forward(String op, Map<String, Object> arguments) {
        Map<String, String> args = new LinkedHashMap<>();
        if (arguments != null) {
            arguments.forEach((key, value) -> {
                if (key == null || value == null) return;
                String flag = switch (key) {
                    case "runId" -> "run-id";
                    case "chatAfter" -> "chat-after";
                    default -> key;
                };
                args.put(flag, value instanceof Map<?, ?> || value instanceof List<?> ? json(value) : String.valueOf(value));
            });
        }
        WorkbenchServices services = PopupMcpCommands.currentServices();
        if (services == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("op", op);
            body.put("args", args);
            body.put("forwarded", false);
            return body;
        }
        Object value = services.agentCommand(op, args);
        return value == null ? Map.of("status", "UNAVAILABLE", "op", op) : value;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("ui: arguments are not JSON-serializable: " + failure.getOriginalMessage());
        }
    }

    static Map<String, Object> uiSchema() {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("type", "object");
        op.put("properties", Map.of(
                "c", Map.of("type", "string", "enum", COMPONENTS, "description", "Component."),
                "t", Map.of("type", "string", "description", "Target: id, h:<handle>, CSS, text:<words>, role:<role>[name=..], x:<xpath>.")));
        op.put("required", List.of("c"));
        op.put("additionalProperties", true);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("ops", Map.of("type", "array", "items", op, "minItems", 1, "maxItems", 50,
                "description", "Ops in order, e.g. [{\"c\":\"guide\",\"steps\":[{\"t\":\"#email\",\"say\":\"Type your email\",\"do\":\"input\"}]}]."));
        properties.put("verbose", Map.of("type", "boolean", "description", "Full replies instead of the compact one."));
        properties.put("scope", Map.of("type", "string", "enum", List.of("page", "origin", "run"), "description", "Where it comes back after reloads."));
        properties.put("ttl", Map.of("type", "integer", "description", "Seconds before the saved page state expires."));
        properties.put("runId", Map.of("type", "string", "description", "Run id."));
        return object(properties, List.of("ops"));
    }

    static Map<String, Object> pollSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("after", Map.of("type", "integer", "description", "Event cursor: next from the last ui_poll."));
        properties.put("chatAfter", Map.of("type", "string", "description", "Chat cursor: chatNext from the last ui_poll."));
        properties.put("wait", Map.of("type", "number", "description", "Seconds to wait for something, up to 25."));
        properties.put("runId", Map.of("type", "string", "description", "Run id."));
        return object(properties, List.of());
    }

    static Map<String, Object> catalogSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("component", Map.of("type", "string", "enum", COMPONENTS, "description", "One component only."));
        return object(properties, List.of());
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }
}
