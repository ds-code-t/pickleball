package tools.dscode.workbench.mcp;

import tools.dscode.workbench.WorkbenchServices;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Public agent commands on the Workbench MCP server.
 * {@code query}, {@code click}, {@code type}, and {@code observe} forward to the domengine CLI.
 * {@code launch} and {@code surfaces} forward to {@code WorkbenchServices.agentCommand}.
 * {@code snapshot} returns G3 CompositeSnapshot JSON.
 * Popup commands register here or through {@link McpCommandContributor}.
 */
public final class McpRegistry {
    public record Command(String name, String description, Map<String, Object> schema, Function<Map<String, Object>, Object> handler) {
    }

    private static final CopyOnWriteArrayList<Command> EXTRA = new CopyOnWriteArrayList<>();

    private McpRegistry() {
    }

    public static void register(Command command) {
        if (command == null || command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("error: MCP command name is blank.\nfix: Pass a name.");
        }
        EXTRA.add(command);
    }

    public static void register(String name, String description, Map<String, Object> schema, Function<Map<String, Object>, Object> handler) {
        register(new Command(name, description, schema, handler));
    }

    public static void resetForTests() {
        EXTRA.clear();
    }

    public static List<Command> extras() {
        return List.copyOf(EXTRA);
    }

    static List<Command> builtins(WorkbenchServices services, Function<List<String>, Object> engine) {
        return List.of(
                engineCommand("query", "Run one engine query. This is a public agent command.", engine),
                engineCommand("click", "Click through the engine CLI.", engine),
                engineCommand("type", "Type through the engine CLI.", engine),
                snapshot(),
                engineCommand("observe", "Read observe events through the engine CLI.", engine),
                agent("launch", "Launch a browser template through AgentControl.", services, "launch"),
                surfaces(services)
        );
    }

    static void contribute(McpCommandContributor.Sink sink) {
        ServiceLoader.load(McpCommandContributor.class).forEach(contributor -> contributor.contribute(sink));
    }

    private static Command snapshot() {
        return new Command(
                "snapshot",
                "Return a CompositeSnapshot JSON document. G3 owns the schema.",
                schema(Map.of(
                        "kind", stringProperty("browser, desktop, offline, or page."),
                        "session", stringProperty("Engine session id when kind is browser."),
                        "platform", stringProperty("Optional platform name.")
                )),
                SnapshotCommand::render);
    }

    private static Command engineCommand(String name, String description, Function<List<String>, Object> engine) {
        return new Command(name, description, argumentSchema(name), arguments -> engine.apply(argv(name, arguments)));
    }

    private static Command agent(String name, String description, WorkbenchServices services, String op) {
        return new Command(name, description, argumentSchema(name), arguments -> {
            Object value = services.agentCommand(op, strings(arguments));
            return value == null ? Map.of("status", "UNAVAILABLE", "op", op) : value;
        });
    }

    private static Command surfaces(WorkbenchServices services) {
        return new Command(
                "surfaces",
                "Page-surface operations. With no op, lists them. With op, forwards to AgentControl.",
                schema(Map.of(
                        "op", stringProperty("html-write, html-replace, html-insert, html-remove, html-cleanup, overlay, overlay-poll, overlay-remove, highlight, or unhighlight."),
                        "html", stringProperty("Structural HTML for a write, replace, or insert."),
                        "handle", stringProperty("Optional target handle.")
                )),
                arguments -> {
                    Object op = arguments == null ? null : arguments.get("op");
                    if (op == null || String.valueOf(op).isBlank()) {
                        return Map.of(
                                "ok", true,
                                "entry", "mcp",
                                "commands", List.of(
                                        "html-write", "html-replace", "html-insert", "html-remove", "html-cleanup",
                                        "overlay", "overlay-poll", "overlay-remove", "highlight", "unhighlight"));
                    }
                    Object value = services.agentCommand(String.valueOf(op), strings(arguments));
                    return value == null ? Map.of("status", "UNAVAILABLE", "op", String.valueOf(op)) : value;
                });
    }

    static List<String> argv(String command, Map<String, Object> arguments) {
        List<String> argv = new ArrayList<>();
        argv.add(command);
        Object raw = arguments == null ? null : arguments.get("argv");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) argv.add(String.valueOf(item));
            }
            return argv;
        }
        flag(argv, arguments, "session", "--session");
        flag(argv, arguments, "selector", "--selector");
        flag(argv, arguments, "text", "--text");
        flag(argv, arguments, "file", "--file");
        flag(argv, arguments, "cdp", "--cdp");
        return argv;
    }

    private static void flag(List<String> argv, Map<String, Object> arguments, String field, String flag) {
        if (arguments == null || arguments.get(field) == null) return;
        String value = String.valueOf(arguments.get(field));
        if (!value.isBlank()) {
            argv.add(flag);
            argv.add(value);
        }
    }

    private static Map<String, String> strings(Map<String, Object> arguments) {
        Map<String, String> forwarded = new LinkedHashMap<>();
        if (arguments == null) return forwarded;
        arguments.forEach((key, value) -> {
            if (key != null && value != null) forwarded.put(key, String.valueOf(value));
        });
        return forwarded;
    }

    private static Map<String, Object> argumentSchema(String command) {
        return schema(Map.of(
                "argv", Map.of("type", "array", "description", "Flags after " + command + ".", "items", Map.of("type", "string")),
                "session", stringProperty("Session id."),
                "selector", stringProperty("Engine selector."),
                "text", stringProperty("Text for type or observe."),
                "file", stringProperty("HTML file for an offline command.")
        ));
    }

    private static Map<String, Object> schema(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }
}
