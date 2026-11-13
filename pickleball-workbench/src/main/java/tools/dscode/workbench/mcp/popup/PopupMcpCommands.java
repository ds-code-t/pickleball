package tools.dscode.workbench.mcp.popup;

import tools.dscode.workbench.WorkbenchServices;
import tools.dscode.workbench.mcp.McpCommandContributor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Popup MCP tools. Each handler forwards the agent op and arguments. */
public final class PopupMcpCommands implements McpCommandContributor {
    @Override
    public void contribute(Sink sink) {
        sink.register("agent_popup_open", "Open the agent surface popup.", schema(), arguments -> forward("popup-open", arguments));
        sink.register("agent_popup_status", "Read agent surface popup status.", schema(), arguments -> forward("popup-status", arguments));
        sink.register("agent_popup_close", "Close the agent surface popup.", schema(), arguments -> forward("popup-close", arguments));
    }

    static Object forward(String op, Map<String, Object> arguments) {
        Map<String, String> args = new LinkedHashMap<>();
        if (arguments != null) {
            arguments.forEach((key, value) -> {
                if (key != null && value != null) args.put(key, String.valueOf(value));
            });
        }
        WorkbenchServices services = currentServices();
        if (services == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("op", op);
            body.put("args", args);
            body.put("forwarded", false);
            return body;
        }
        Object value = services.agentCommand(op, args);
        return value == null ? Map.of("status", "UNAVAILABLE", "op", op, "args", args) : value;
    }

    private static WorkbenchServices currentServices() {
        try {
            Class<?> type = Class.forName("tools.dscode.workbench.mcp.WorkbenchMcpTools");
            var current = type.getDeclaredField("CURRENT");
            current.setAccessible(true);
            Object tools = ((ThreadLocal<?>) current.get(null)).get();
            if (tools == null) return null;
            var field = type.getDeclaredField("services");
            field.setAccessible(true);
            return (WorkbenchServices) field.get(tools);
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    private static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("runId", Map.of("type", "string", "description", "Run id."));
        properties.put("target", Map.of("type", "string", "description", "auto, page, popup, or native."));
        properties.put("pane", Map.of("type", "string", "description", "instructions or diagram."));
        properties.put("text", Map.of("type", "string", "description", "Optional text or caption."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }
}
