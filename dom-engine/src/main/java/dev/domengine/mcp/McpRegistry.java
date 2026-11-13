package dev.domengine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The MCP command catalogue. Builtins are the public agent commands.
 * Extra commands are registered in code or by service loader and do not replace a builtin name.
 */
public final class McpRegistry {
    private static final ConcurrentHashMap<String, McpCommand> EXTRA = new ConcurrentHashMap<>();

    private McpRegistry() {
    }

    public static void register(McpCommand command) {
        if (command == null || command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("error: MCP command name is blank.\nfix: Pass a name.");
        }
        EXTRA.put(command.name(), command);
    }

    public static void resetForTests() {
        EXTRA.clear();
    }

    public static List<McpCommand> commands() {
        Map<String, McpCommand> all = new LinkedHashMap<>();
        for (McpCommand command : builtins()) all.put(command.name(), command);
        ServiceLoader.load(McpCommand.class).forEach(command -> all.putIfAbsent(command.name(), command));
        EXTRA.forEach(all::putIfAbsent);
        return List.copyOf(all.values());
    }

    public static McpCommand find(String name) {
        for (McpCommand command : commands()) {
            if (command.name().equals(name)) return command;
        }
        return null;
    }

    public static List<String> names() {
        return commands().stream().map(McpCommand::name).toList();
    }

    static List<McpCommand> builtins() {
        return List.of(
                forward("domengine", "Forward an argv list to the domengine CLI", true),
                forward("query", "Run a domengine query. Arguments become CLI flags.", false),
                forward("click", "Click through the domengine CLI.", false),
                forward("type", "Type through the domengine CLI.", false),
                snapshot(),
                forward("observe", "Read observe events through the domengine CLI.", false),
                launch(),
                surfaces()
        );
    }

    private static McpCommand forward(String name, String description, boolean rawArgv) {
        return new McpCommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public ObjectNode inputSchema() {
                return schema();
            }

            @Override
            public ObjectNode call(JsonNode arguments) {
                EngineLog.info("mcp", "call " + name);
                List<String> argv = rawArgv ? raw(arguments) : flags(name, arguments);
                CliResult result = DomEngineMain.dispatch(argv);
                String text = result.exit() == 0 ? result.stdout() : result.stderr();
                return tool(text == null ? "" : text, result.exit() != 0);
            }
        };
    }

    private static McpCommand snapshot() {
        return new McpCommand() {
            @Override
            public String name() {
                return "snapshot";
            }

            @Override
            public String description() {
                return "Return a CompositeSnapshot JSON document. G3 owns the schema.";
            }

            @Override
            public ObjectNode inputSchema() {
                return schema();
            }

            @Override
            public ObjectNode call(JsonNode arguments) {
                EngineLog.info("mcp", "call snapshot");
                try {
                    return tool(Jsons.compact(SnapshotDispatch.render(arguments)), false);
                } catch (RuntimeException failure) {
                    String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
                    return tool(message, true);
                }
            }
        };
    }

    private static McpCommand launch() {
        return new McpCommand() {
            @Override
            public String name() {
                return "launch";
            }

            @Override
            public String description() {
                return "Launch is a root agent command. The domengine server names it and does not start a browser.";
            }

            @Override
            public ObjectNode inputSchema() {
                return schema();
            }

            @Override
            public ObjectNode call(JsonNode arguments) {
                EngineLog.info("mcp", "call launch");
                return tool(
                        "error: launch is not a domengine command.\n"
                                + "fix: Call the workbench MCP launch command, or AgentControl launch.\n",
                        true);
            }
        };
    }

    private static McpCommand surfaces() {
        return new McpCommand() {
            @Override
            public String name() {
                return "surfaces";
            }

            @Override
            public String description() {
                return "List page-surface operations. Injection runs in the root agent, not in this engine process.";
            }

            @Override
            public ObjectNode inputSchema() {
                return schema();
            }

            @Override
            public ObjectNode call(JsonNode arguments) {
                EngineLog.info("mcp", "call surfaces");
                ObjectNode body = Jsons.object();
                body.put("ok", true);
                body.put("entry", "mcp");
                var commands = body.putArray("commands");
                for (String name : List.of(
                        "html-write", "html-replace", "html-insert", "html-remove", "html-cleanup",
                        "overlay", "overlay-poll", "overlay-remove", "highlight", "unhighlight")) {
                    commands.add(name);
                }
                return tool(Jsons.compact(body), false);
            }
        };
    }

    static ObjectNode schema() {
        ObjectNode schema = Jsons.object();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode argv = properties.putObject("argv");
        argv.put("type", "array");
        argv.putObject("items").put("type", "string");
        argv.put("description", "CLI arguments. For domengine this is the whole argv. For other commands it follows the command name.");
        for (String name : List.of("session", "selector", "text", "file", "cdp", "url", "kind", "platform", "html")) {
            properties.putObject(name).put("type", "string");
        }
        return schema;
    }

    static ObjectNode tool(String text, boolean error) {
        ObjectNode body = Jsons.object();
        ObjectNode content = body.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", text == null ? "" : text);
        if (error) body.put("isError", true);
        return body;
    }

    private static List<String> raw(JsonNode arguments) {
        List<String> argv = new ArrayList<>();
        JsonNode raw = arguments == null ? null : arguments.get("argv");
        if (raw != null && raw.isArray()) {
            for (JsonNode item : raw) argv.add(item.asText(""));
        }
        return argv;
    }

    private static List<String> flags(String command, JsonNode arguments) {
        List<String> argv = new ArrayList<>();
        argv.add(command);
        JsonNode raw = arguments == null ? null : arguments.get("argv");
        if (raw != null && raw.isArray() && !raw.isEmpty()) {
            for (JsonNode item : raw) argv.add(item.asText(""));
            return argv;
        }
        flag(argv, arguments, "session", "--session");
        flag(argv, arguments, "selector", "--selector");
        flag(argv, arguments, "text", "--text");
        flag(argv, arguments, "file", "--file");
        flag(argv, arguments, "cdp", "--cdp");
        flag(argv, arguments, "url", "--url");
        return argv;
    }

    private static void flag(List<String> argv, JsonNode arguments, String field, String flag) {
        if (arguments == null || arguments.get(field) == null || arguments.get(field).isNull()) return;
        String value = arguments.get(field).asText("");
        if (!value.isBlank()) {
            argv.add(flag);
            argv.add(value);
        }
    }
}
