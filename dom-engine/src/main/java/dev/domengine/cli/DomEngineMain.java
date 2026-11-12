package dev.domengine.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.EngineInfo;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.session.BrowserSession;
import dev.domengine.session.SessionRegistry;
import dev.domengine.tree.LiveTree;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CLI surface. {@code serve --stdio} reads one JSON line ({@code id}, {@code argv}) and writes one JSON line.
 * Workbench, MCP, and Gradle only forward here.
 */
public final class DomEngineMain {
    private DomEngineMain() {
    }

    public static void main(String[] args) {
        if (isStdio(args)) {
            serveStdio();
            return;
        }
        CliResult result = run(args);
        System.out.print(result.stdout());
        System.err.print(result.stderr());
        if (result.exit() != 0) System.exit(result.exit());
    }

    public static CliResult run(String[] args) {
        EngineLog.nextCorrelation();
        EngineLog.info("cli", String.join(" ", args == null ? new String[0] : args));
        return dispatch(args == null ? List.of() : List.of(args));
    }

    public static CliResult dispatch(List<String> argv) {
        List<String> args = argv == null ? List.of() : argv;
        if (args.isEmpty() || wantsHelp(args)) {
            return CliResult.ok(helpText());
        }
        if (args.size() == 1 && ("--version".equals(args.get(0)) || "-V".equals(args.get(0)))) {
            return CliResult.ok(EngineInfo.NAME + " " + EngineInfo.VERSION + "\n");
        }
        String command = args.get(0);
        if ("session".equals(command)) {
            return session(args);
        }
        Map<String, String> flags = flags(args.subList(1, args.size()));
        if (flags.containsKey("__usage")) {
            return error(2, "usage", flags.get("__usage"));
        }
        try {
            return switch (command) {
                case "version" -> CliResult.ok(Jsons.pretty(version()));
                case "capabilities" -> CliResult.ok(Jsons.pretty(capabilities()));
                case "attach" -> sessionVerb("attach", flags);
                case "snapshot" -> snapshot(flags);
                case "serve" -> serveRefused();
                default -> error(2, "usage", "unknown command: " + command);
            };
        } catch (CdpException failure) {
            String message = failure.getMessage() == null ? "failed" : failure.getMessage();
            String code = codeFor(message);
            int exit = "usage".equals(code) ? 2 : 1;
            return error(exit, code, message);
        } catch (RuntimeException failure) {
            return error(1, "failed", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    static void serveStdio() {
        serve(System.in, System.out);
    }

    public static void serve(java.io.InputStream in, java.io.OutputStream out) {
        EngineLog.info("serve", "stdio");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode request;
                try {
                    request = Jsons.MAPPER.readTree(line);
                } catch (Exception failure) {
                    writeStdio(out, "", 1, "", Jsons.pretty(Jsons.error("invalid", "stdio request is not JSON")));
                    continue;
                }
                String id = request.path("id").asText("");
                List<String> argv = new ArrayList<>();
                JsonNode raw = request.get("argv");
                if (raw != null && raw.isArray()) {
                    for (JsonNode item : raw) argv.add(item.asText(""));
                }
                if (!argv.isEmpty() && "serve".equals(argv.get(0))) {
                    writeStdio(out, id, 1, "", Jsons.pretty(Jsons.error("invalid", "serve refuses to start a second server")));
                    continue;
                }
                EngineLog.nextCorrelation();
                CliResult result = dispatch(argv);
                writeStdio(out, id, result.exit(), result.stdout(), result.stderr());
            }
        } catch (Exception failure) {
            EngineLog.error("serve", failure.getMessage());
        }
    }

    private static void writeStdio(java.io.OutputStream out, String id, int exit, String stdout, String stderr) {
        ObjectNode node = Jsons.object();
        node.put("id", id);
        node.put("exit", exit);
        node.put("stdout", stdout == null ? "" : stdout);
        node.put("stderr", stderr == null ? "" : stderr);
        try {
            out.write((Jsons.compact(node) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (java.io.IOException failure) {
            throw new CdpException("stdio write: " + failure.getMessage());
        }
    }

    private static CliResult serveRefused() {
        return error(1, "invalid", "serve refuses to start a second server");
    }

    private static ObjectNode version() {
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("name", EngineInfo.NAME);
        node.put("version", EngineInfo.VERSION);
        return node;
    }

    static ObjectNode capabilities() {
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("name", EngineInfo.NAME);
        node.put("version", EngineInfo.VERSION);
        array(node, "commands", List.of("version", "capabilities", "session", "attach", "snapshot", "serve"));
        array(node, "session_verbs", List.of("create", "list", "attach", "close"));
        array(node, "engines", List.of());
        array(node, "actions", List.of());
        array(node, "query_flags", List.of());
        array(node, "desktop", List.of());
        ObjectNode logging = node.putObject("logging");
        logging.put("default", "off");
        array(logging, "entities", EngineInfo.LOG_ENTITIES);
        node.putObject("attach").put("requires", "i_accept_local_attach");
        return node;
    }

    private static CliResult session(List<String> args) {
        List<String> rest = args.size() <= 1 ? List.of() : args.subList(1, args.size());
        String verb = rest.isEmpty() || rest.get(0).startsWith("--") ? "create" : rest.get(0);
        List<String> flagArgs = rest.isEmpty() || rest.get(0).startsWith("--") ? rest : rest.subList(1, rest.size());
        Map<String, String> flags = flags(flagArgs);
        if (flags.containsKey("__usage")) return error(2, "usage", flags.get("__usage"));
        return sessionVerb(verb, flags);
    }

    private static CliResult sessionVerb(String verb, Map<String, String> flags) {
        try {
            return switch (verb) {
                case "create" -> CliResult.ok(Jsons.pretty(create(flags).record()));
                case "attach" -> CliResult.ok(Jsons.pretty(attach(flags).record()));
                case "list" -> CliResult.ok(Jsons.pretty(SessionRegistry.get().list()));
                case "close" -> CliResult.ok(Jsons.pretty(SessionRegistry.get().close(required(flags, "id"))));
                default -> error(2, "usage", "unknown session verb: " + verb);
            };
        } catch (CdpException failure) {
            String message = failure.getMessage() == null ? "failed" : failure.getMessage();
            return error(1, codeFor(message), message);
        }
    }

    private static dev.domengine.session.BrowserSession create(Map<String, String> flags) {
        String id = flags.getOrDefault("id", "s" + System.nanoTime());
        boolean accept = flag(flags, "i-accept-local-attach");
        boolean headed = flag(flags, "headed");
        boolean process = flag(flags, "process");
        String profile = flags.get("profile");
        return SessionRegistry.get().create(
                id,
                flags.get("url"),
                flags.get("cdp"),
                headed,
                process,
                accept,
                profile == null ? null : Path.of(profile),
                allow(flags.get("allow"))
        );
    }

    private static BrowserSession attach(Map<String, String> flags) {
        if (!flags.containsKey("cdp")) throw new CdpException("attach requires --cdp");
        Map<String, String> copy = new LinkedHashMap<>(flags);
        if (!copy.containsKey("id") || copy.get("id").isBlank()) copy.put("id", "s" + System.nanoTime());
        if (!flag(copy, "i-accept-local-attach") && !"1".equals(System.getenv("COMPOSITE_ATTACH"))) {
            throw new CdpException("attach requires --i-accept-local-attach or COMPOSITE_ATTACH=1");
        }
        return SessionRegistry.get().create(
                copy.get("id"),
                null,
                copy.get("cdp"),
                false,
                flag(copy, "process"),
                true,
                null,
                allow(copy.get("allow"))
        );
    }

    private static CliResult snapshot(Map<String, String> flags) {
        BrowserSession session;
        boolean ephemeral = false;
        if (flags.containsKey("session")) {
            session = SessionRegistry.get().require(flags.get("session"));
        } else if (flags.containsKey("cdp")) {
            if (!flag(flags, "i-accept-local-attach") && !"1".equals(System.getenv("COMPOSITE_ATTACH"))) {
                throw new CdpException("attach requires --i-accept-local-attach or COMPOSITE_ATTACH=1");
            }
            session = SessionRegistry.get().create(
                    "snap-" + System.nanoTime(),
                    null,
                    flags.get("cdp"),
                    false,
                    false,
                    true,
                    null,
                    List.of()
            );
            ephemeral = true;
        } else {
            return error(2, "usage", "snapshot requires --session or --cdp");
        }
        try {
            LiveTree tree = session.tree();
            return CliResult.ok(Jsons.pretty(tree.toJson()));
        } finally {
            if (ephemeral) SessionRegistry.get().close(session.id);
        }
    }

    private static List<String> allow(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> hosts = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) hosts.add(part.trim());
        }
        return hosts;
    }

    private static String required(Map<String, String> flags, String name) {
        String value = flags.get(name);
        if (value == null || value.isBlank()) throw new CdpException("missing --" + name);
        return value;
    }

    private static boolean flag(Map<String, String> flags, String name) {
        return "true".equals(flags.get(name));
    }

    static Map<String, String> flags(List<String> args) {
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            if (!token.startsWith("--")) {
                flags.put("__usage", "unexpected argument: " + token);
                return flags;
            }
            String name = token.substring(2);
            if (isBoolean(name)) {
                flags.put(name, "true");
                continue;
            }
            if (i + 1 >= args.size() || args.get(i + 1).startsWith("--")) {
                flags.put("__usage", "missing value for --" + name);
                return flags;
            }
            flags.put(name, args.get(++i));
        }
        return flags;
    }

    private static boolean isBoolean(String name) {
        return switch (name) {
            case "process", "headed", "i-accept-local-attach", "follow", "stdio", "strict", "explain" -> true;
            default -> false;
        };
    }

    private static boolean isStdio(String[] args) {
        if (args == null) return false;
        boolean serve = false;
        boolean stdio = false;
        for (String arg : args) {
            if ("serve".equals(arg)) serve = true;
            if ("--stdio".equals(arg)) stdio = true;
        }
        return serve && stdio;
    }

    private static boolean wantsHelp(List<String> args) {
        return args.stream().anyMatch(arg -> "--help".equals(arg) || "-h".equals(arg) || "help".equals(arg));
    }

    private static String codeFor(String message) {
        String text = message.toLowerCase(Locale.ROOT);
        if (text.contains("not found") || text.contains("no page target")) return "not_found";
        if (text.contains("timed out") || text.contains("timeout")) return "timeout";
        if (text.contains("requires") || text.contains("missing")) return "invalid";
        return "failed";
    }

    private static CliResult error(int exit, String code, String message) {
        EngineLog.error("cli", code + " " + message);
        return CliResult.fail(exit, Jsons.pretty(Jsons.error(code, message)));
    }

    private static void array(ObjectNode node, String name, List<String> values) {
        ArrayNode array = node.putArray(name);
        for (String value : values) array.add(value);
    }

    private static String helpText() {
        return """
                domengine %s
                version
                capabilities
                session create|list|attach|close
                attach --cdp URL --i-accept-local-attach
                snapshot --session ID | --cdp URL --i-accept-local-attach
                serve --stdio
                """.formatted(EngineInfo.VERSION);
    }
}
