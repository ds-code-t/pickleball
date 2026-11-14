package dev.domengine.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.EngineInfo;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.premap.OneMatchException;
import dev.domengine.query.LiveXPath;
import dev.domengine.session.BrowserSession;
import dev.domengine.session.SessionRegistry;
import dev.domengine.action.Observe;
import dev.domengine.action.PageActions;
import dev.domengine.desktop.DesktopTree;
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
    private static final ThreadLocal<Boolean> IN_STDIO = ThreadLocal.withInitial(() -> Boolean.FALSE);

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
        List<String> args = new ArrayList<>(argv == null ? List.of() : argv);
        // --session-dir is global on the Rust CLI: `composite --session-dir DIR query ...`
        // and `composite query ... --session-dir DIR` both name the registry.
        Path globalSessionDir = peelSessionDir(args);
        if (globalSessionDir != null) SessionRegistry.get().useDirectory(globalSessionDir);
        if (args.isEmpty() || wantsHelp(args)) {
            return CliResult.ok(helpText());
        }
        if ("log".equals(args.get(0))) {
            return logs(args);
        }
        if (args.size() == 1 && ("--version".equals(args.get(0)) || "-V".equals(args.get(0)))) {
            return CliResult.ok(EngineInfo.NAME + " " + EngineInfo.VERSION + "\n");
        }
        String command = args.get(0);
        if ("session".equals(command)) {
            return session(args);
        }
        if ("desktop".equals(command)) {
            return desktop(args);
        }
        if ("mcp".equals(command)) {
            return mcp();
        }
        List<String> tail = new ArrayList<>(args.subList(1, args.size()));
        String positional = null;
        if (acceptsFile(command)) {
            List<String> kept = new ArrayList<>();
            boolean flagged = false;
            for (String token : tail) {
                if (!flagged && positional == null && !token.startsWith("--")) {
                    positional = token;
                    continue;
                }
                if (token.startsWith("--")) flagged = true;
                kept.add(token);
            }
            tail = kept;
        }
        Map<String, String> flags = flags(tail);
        if (positional != null && !flags.containsKey("file")) flags.put("file", positional);
        if (flags.containsKey("__usage")) {
            return error(2, "usage", flags.get("__usage"));
        }
        try {
            return switch (command) {
                case "version" -> CliResult.ok(Jsons.pretty(version()));
                case "capabilities" -> CliResult.ok(Jsons.pretty(capabilities()));
                case "attach" -> sessionVerb("attach", flags);
                case "snapshot" -> snapshot(flags);
                case "premap" -> premap(flags);
                case "query" -> query(flags);
                case "resolve" -> resolve(flags);
                case "info" -> info(flags);
                case "recommend" -> recommend(flags);
                case "validate" -> validate(flags);
                case "capture" -> capture(flags);
                case "conform" -> conform(flags);
                case "click", "type", "scroll", "submit", "key", "dblclick", "contextclick", "drag", "eval", "hover" ->
                        action(command, flags);
                case "screenshot" -> screenshot(flags);
                case "observe", "wait" -> observe(command, flags);
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
        IN_STDIO.set(Boolean.TRUE);
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
        } finally {
            IN_STDIO.set(Boolean.FALSE);
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
        array(node, "commands", List.of(
                "version", "capabilities", "session", "attach", "snapshot", "premap", "query", "resolve",
                "info", "recommend", "validate", "capture", "conform", "log",
                "click", "type", "scroll", "submit", "key", "dblclick", "contextclick", "drag", "hover", "eval",
                "screenshot", "observe", "wait", "desktop", "mcp", "serve"
        ));
        array(node, "session_verbs", List.of("create", "list", "attach", "close", "join", "log"));
        ObjectNode observe = node.putObject("observe");
        observe.put("events", true);
        observe.put("source", "cdp-session");
        observe.put("buffer", dev.domengine.session.SessionEvents.CAPACITY);
        array(observe, "kinds", List.of("network", "navigation", "console", "log"));
        array(observe, "flags", List.of("session", "cdp", "type", "url", "since", "until", "limit", "wait-ms"));
        array(node, "engines", dev.domengine.query.Query.ENGINES);
        array(node, "actions", PageActions.VERBS);
        array(node, "query_flags", dev.domengine.query.Query.QUERY_FLAGS);
        node.put("eval_nodes", "256");
        array(node, "row_states", dev.domengine.query.RowStates.NAMES);
        ObjectNode verbFlags = node.putObject("verb_flags");
        for (Map.Entry<String, List<String>> entry : VerbFlags.VERBS.entrySet()) {
            array(verbFlags, entry.getKey(), entry.getValue());
        }
        array(node, "desktop", DesktopTree.VERBS);
        ObjectNode logging = node.putObject("logging");
        logging.put("default", "off");
        array(logging, "entities", EngineInfo.LOG_ENTITIES);
        node.putObject("attach").put("requires", "i_accept_local_attach");
        node.put("snapshot_schema", EngineInfo.SNAPSHOT_SCHEMA);
        node.put("premap", EngineInfo.PREMAP);
        return node;
    }

    private static CliResult session(List<String> args) {
        List<String> rest = new ArrayList<>(args.size() <= 1 ? List.of() : args.subList(1, args.size()));
        Path sessionDir = peelSessionDir(rest);
        if (sessionDir != null) SessionRegistry.get().useDirectory(sessionDir);
        String verb = rest.isEmpty() || rest.get(0).startsWith("--") ? "create" : rest.get(0);
        List<String> flagArgs = rest.isEmpty() || rest.get(0).startsWith("--") ? rest : rest.subList(1, rest.size());
        Map<String, String> flags = flags(flagArgs);
        if (flags.containsKey("__usage")) return error(2, "usage", flags.get("__usage"));
        if (flags.containsKey("session-dir")) SessionRegistry.get().useDirectory(Path.of(flags.get("session-dir")));
        return sessionVerb(verb, flags);
    }

    /** {@code --session-dir} is global on the Rust CLI, so it may appear before the verb. */
    private static Path peelSessionDir(List<String> args) {
        Path dir = null;
        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            String value = null;
            if ("--session-dir".equals(token) && i + 1 < args.size()) {
                value = args.get(i + 1);
                args.remove(i + 1);
                args.remove(i);
                i--;
            } else if (token.startsWith("--session-dir=")) {
                value = token.substring("--session-dir=".length());
                args.remove(i);
                i--;
            }
            if (value != null && !value.isBlank()) dir = Path.of(value);
        }
        return dir;
    }

    private static CliResult sessionVerb(String verb, Map<String, String> flags) {
        try {
            return switch (verb) {
                case "create" -> CliResult.ok(Jsons.pretty(create(flags).record()));
                case "attach" -> CliResult.ok(Jsons.pretty(attach(flags).record()));
                case "list" -> CliResult.ok(Jsons.pretty(SessionRegistry.get().list()));
                case "close" -> CliResult.ok(Jsons.pretty(SessionRegistry.get().close(flags.get("id"))));
                case "join" -> CliResult.ok(Jsons.pretty(joinSession(flags)));
                case "log" -> sessionLog(flags);
                default -> error(2, "usage", "unknown session verb: " + verb);
            };
        } catch (CdpException failure) {
            String message = failure.getMessage() == null ? "failed" : failure.getMessage();
            return error(1, codeFor(message), message);
        }
    }

    private static ObjectNode joinSession(Map<String, String> flags) {
        String id = flags.get("id");
        if (id != null && !id.isBlank()) return SessionRegistry.get().join(id);
        String cdp = flags.get("cdp");
        if (cdp != null && !cdp.isBlank()) {
            return SessionRegistry.get().joinCdp(cdp, flag(flags, "i-accept-local-attach") || "1".equals(System.getenv("COMPOSITE_ATTACH")));
        }
        throw new CdpException("session join needs --id or --cdp");
    }

    private static CliResult sessionLog(Map<String, String> flags) {
        if (flag(flags, "follow") && Boolean.TRUE.equals(IN_STDIO.get())) {
            return error(1, "invalid", "session log --follow streams on the CLI; it does not run inside serve --stdio");
        }
        String id = required(flags, "id");
        if (flag(flags, "follow")) return CliResult.ok(SessionRegistry.get().follow(id));
        return CliResult.ok(Jsons.pretty(SessionRegistry.get().events(id)));
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
                allow(flags.get("allow")),
                flags.get("target")
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
                allow(copy.get("allow")),
                copy.get("target")
        );
    }

    private static CliResult desktopSnapshot(Map<String, String> flags) {
        com.fasterxml.jackson.databind.node.ObjectNode json = DesktopTree.snapshot(required(flags, "id"), flags);
        String dir = flags.get("out-dir");
        if (dir != null && !dir.isBlank()) {
            try {
                Path out = Path.of(dir);
                java.nio.file.Files.createDirectories(out);
                String shot = flags.get("screenshot");
                if (shot != null && !shot.isBlank()) {
                    java.nio.file.Files.copy(
                            Path.of(shot),
                            out.resolve("screenshot.png"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    );
                }
                java.nio.file.Files.writeString(out.resolve("snapshot.json"), Jsons.pretty(json));
            } catch (java.io.IOException failure) {
                return error(1, "failed", "desktop snapshot out-dir: " + failure.getMessage());
            }
        }
        return CliResult.ok(Jsons.pretty(json));
    }

    private static CliResult premap(Map<String, String> flags) {
        String unknown = VerbFlags.unrecognized("premap", flags);
        if (unknown != null) return error(2, "usage", "unrecognized flag --" + unknown + " for premap");
        if (!flags.containsKey("file") || flags.get("file").isBlank()) return error(2, "usage", "premap requires --file");
        dev.domengine.tree.DomNode root;
        try {
            root = dev.domengine.query.HtmlTree.parse(java.nio.file.Files.readString(Path.of(flags.get("file"))));
        } catch (java.io.IOException failure) {
            return error(1, "failed", "premap file: " + failure.getMessage());
        }
        com.fasterxml.jackson.databind.node.ObjectNode json = dev.domengine.snapshot.CompositeSnapshots.offline(
                root, dev.domengine.snapshot.SnapshotOptions.fromFlags(flags));
        String dir = flags.get("out-dir");
        if (dir != null && !dir.isBlank()) {
            try {
                Path out = Path.of(dir);
                java.nio.file.Files.createDirectories(out);
                String shot = flags.get("screenshot");
                if (shot != null && !shot.isBlank()) {
                    java.nio.file.Files.copy(Path.of(shot), out.resolve("screenshot.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                java.nio.file.Files.writeString(out.resolve("snapshot.json"), Jsons.pretty(json));
            } catch (java.io.IOException failure) {
                return error(1, "failed", "premap out-dir: " + failure.getMessage());
            }
        }
        return CliResult.ok(Jsons.pretty(json));
    }

    private static CliResult query(Map<String, String> flags) {
        String unknown = VerbFlags.unrecognized("query", flags);
        if (unknown != null) return error(2, "usage", "unrecognized flag --" + unknown + " for query");
        dev.domengine.tree.DomNode root;
        if (flags.containsKey("file")) {
            try {
                root = dev.domengine.query.HtmlTree.parse(java.nio.file.Files.readString(java.nio.file.Path.of(flags.get("file"))));
            } catch (java.io.IOException failure) {
                return error(1, "failed", "query file: " + failure.getMessage());
            }
            if (flags.get("wait-for") != null && !flags.get("wait-for").isBlank()) {
                return CliResult.ok(Jsons.pretty(dev.domengine.action.Observe.waitFor(root, flags)));
            }
            return CliResult.ok(Jsons.pretty(dev.domengine.query.Query.run(root, flags)));
        }
        if (flags.containsKey("session") || SessionRegistry.get().joined() != null) {
            BrowserSession session = bound(flags);
            if (session == null) return error(2, "usage", "query requires --file or --session");
            root = session.tree().root;
            root.linkParents();
            LiveXPath.use(session.connection());
            try {
                ObjectNode ran = flags.get("wait-for") == null || flags.get("wait-for").isBlank()
                        ? dev.domengine.query.Query.run(root, flags)
                        : dev.domengine.action.Observe.waitSession(session, flags);
                return CliResult.ok(Jsons.pretty(ran));
            } finally {
                LiveXPath.use(null);
            }
        }
        return error(2, "usage", "query requires --file or --session");
    }

    private static CliResult action(String verb, Map<String, String> flags) {
        String unknown = VerbFlags.unrecognized(verb, flags);
        if (unknown != null) return error(2, "usage", "unrecognized flag --" + unknown + " for " + verb);
        BrowserSession session = bound(flags);
        if (session == null) return error(2, "usage", verb + " requires --session");
        LiveXPath.use(session.connection());
        try {
            ObjectNode result = PageActions.act(session, verb, flags);
            ObjectNode detail = Jsons.object();
            detail.put("ok", true);
            SessionRegistry.get().record(session.id, verb, detail);
            return CliResult.ok(Jsons.pretty(result));
        } finally {
            LiveXPath.use(null);
        }
    }

    private static CliResult screenshot(Map<String, String> flags) {
        BrowserSession session = bound(flags);
        if (session == null) return error(2, "usage", "screenshot requires --session");
        ObjectNode result = Observe.screenshot(session, flags);
        ObjectNode detail = Jsons.object();
        detail.put("ok", true);
        SessionRegistry.get().record(session.id, "screenshot", detail);
        return CliResult.ok(Jsons.pretty(result));
    }

    private static BrowserSession bound(Map<String, String> flags) {
        if (flags.containsKey("session") && flags.get("session") != null && !flags.get("session").isBlank()) {
            return SessionRegistry.get().require(flags.get("session"));
        }
        String joined = SessionRegistry.get().joined();
        if (joined == null || joined.isBlank()) return null;
        return SessionRegistry.get().require(joined);
    }

    private static CliResult observe(String command, Map<String, String> flags) {
        if (!queriesDom(flags)) return observeEvents(command, flags);
        BrowserSession session = null;
        if (flags.containsKey("session") || (SessionRegistry.get().joined() != null && !flags.containsKey("file"))) {
            session = bound(flags);
        }
        dev.domengine.tree.DomNode root = Observe.rootFrom(flags, session);
        if (session != null) LiveXPath.use(session.connection());
        try {
            com.fasterxml.jackson.databind.node.ObjectNode result = session == null
                    ? Observe.waitFor(root, flags)
                    : Observe.waitSession(session, flags);
            result.put("command", command);
            return CliResult.ok(Jsons.pretty(result));
        } finally {
            LiveXPath.use(null);
        }
    }

    /** Query-wait form: a file, or any selector flag the query engine reads. */
    private static boolean queriesDom(Map<String, String> flags) {
        if (flags.containsKey("file")) return true;
        if (flags.containsKey("text")) return true;
        return dev.domengine.query.Query.pipelineOf(flags) != null;
    }

    /**
     * Event form ({@code observe --session ID} with no selector): rows the
     * session's CDP socket buffered since attach or launch, filtered by
     * {@code --type}, {@code --url}, {@code --since}, {@code --until},
     * {@code --limit}. {@code --wait-ms N} waits up to N ms for a match.
     * {@code --cdp} reads the session already on that endpoint; it attaches a
     * new one only with a positive wait and the local-attach opt-in, since a
     * new socket has no history.
     */
    private static CliResult observeEvents(String command, Map<String, String> flags) {
        long waitMs = 0;
        String rawWait = flags.containsKey("wait-ms") ? flags.get("wait-ms") : flags.get("timeout-ms");
        if (rawWait != null && !rawWait.isBlank()) {
            try {
                waitMs = Long.parseLong(rawWait.trim());
            } catch (NumberFormatException failure) {
                return error(2, "usage", "invalid --wait-ms: " + rawWait);
            }
        }
        BrowserSession session;
        String cdp = flags.get("cdp");
        if ((flags.get("session") == null || flags.get("session").isBlank()) && cdp != null && !cdp.isBlank()) {
            session = SessionRegistry.get().findCdp(cdp);
            if (session == null) {
                boolean accept = flag(flags, "i-accept-local-attach") || "1".equals(System.getenv("COMPOSITE_ATTACH"));
                if (waitMs <= 0 || !accept) {
                    return error(1, "failed", "observe failed: no engine CDP Session event buffer for " + cdp
                            + "; a new socket has no history (attach with a positive --wait-ms and --i-accept-local-attach)");
                }
                session = SessionRegistry.get().create("s" + System.nanoTime(), null, cdp, false, false, true, null, List.of());
            }
        } else {
            session = bound(flags);
        }
        if (session == null) {
            return error(2, "usage", "observe needs --session, --cdp, a joined session, or a query selector with --file");
        }
        dev.domengine.session.SessionEvents.Filter filter = dev.domengine.session.SessionEvents.Filter.of(flags);
        List<ObjectNode> rows = session.events().read(filter, waitMs);
        EngineLog.info("observe", "session=" + session.id + " wait_ms=" + waitMs + " count=" + rows.size()
                + " buffered=" + session.events().size());
        ObjectNode result = Jsons.object();
        result.put("ok", true);
        result.put("command", command);
        result.put("source", "cdp-session");
        result.put("sentence", "Events already buffered on the engine CDP Session.");
        result.put("session", session.id);
        result.put("buffered", session.events().size());
        result.put("dropped", session.events().dropped());
        result.put("count", rows.size());
        result.set("events", dev.domengine.session.SessionEvents.toArray(rows));
        return CliResult.ok(Jsons.pretty(result));
    }

    private static CliResult desktop(List<String> args) {
        List<String> rest = args.size() <= 1 ? List.of() : args.subList(1, args.size());
        String verb = rest.isEmpty() || rest.get(0).startsWith("--") ? "list" : rest.get(0);
        List<String> flagArgs = rest.isEmpty() || rest.get(0).startsWith("--") ? rest : rest.subList(1, rest.size());
        Map<String, String> flags = flags(flagArgs);
        if (flags.containsKey("__usage")) return error(2, "usage", flags.get("__usage"));
        try {
            return switch (verb) {
                case "list" -> CliResult.ok(Jsons.pretty(DesktopTree.list()));
                case "attach" -> {
                    String id = flags.getOrDefault("id", "d" + System.nanoTime());
                    boolean live = "true".equals(flags.get("live"));
                    if (live && flags.get("fixture") != null && !flags.get("fixture").isBlank()) {
                        throw new CdpException("desktop attach accepts --fixture or --live, not both");
                    }
                    DesktopTree.Session attached = live
                            ? DesktopTree.attachLive(id, flags.getOrDefault("name", ""))
                            : DesktopTree.attach(id, Path.of(required(flags, "fixture")));
                    com.fasterxml.jackson.databind.node.ObjectNode node = Jsons.object();
                    node.put("ok", true);
                    node.put("id", attached.id());
                    node.put("backend", attached.backend());
                    node.put("live", attached.live());
                    if (!attached.platform().isBlank()) node.put("platform", attached.platform());
                    yield CliResult.ok(Jsons.pretty(node));
                }
                case "walk" -> CliResult.ok(Jsons.pretty(DesktopTree.walk(required(flags, "id"))));
                case "query" -> CliResult.ok(Jsons.pretty(DesktopTree.query(required(flags, "id"), flags)));
                case "resolve" -> CliResult.ok(Jsons.pretty(DesktopTree.resolve(required(flags, "id"), flags)));
                case "click" -> CliResult.ok(Jsons.pretty(DesktopTree.click(required(flags, "id"), flags)));
                case "type" -> CliResult.ok(Jsons.pretty(DesktopTree.type(required(flags, "id"), flags)));
                case "snapshot" -> desktopSnapshot(flags);
                case "close" -> CliResult.ok(Jsons.pretty(DesktopTree.close(required(flags, "id"))));
                default -> error(2, "usage", "unknown desktop verb: " + verb);
            };
        } catch (OneMatchException failure) {
            String message = failure.getMessage() == null ? "not found" : failure.getMessage();
            com.fasterxml.jackson.databind.node.ObjectNode node = Jsons.error(codeFor(message), message);
            node.set("candidates", failure.toJson());
            return CliResult.fail(1, Jsons.pretty(node));
        } catch (CdpException failure) {
            String message = failure.getMessage() == null ? "failed" : failure.getMessage();
            return error(1, codeFor(message), message);
        }
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
        } else if ((session = bound(flags)) == null) {
            return error(2, "usage", "snapshot requires --session or --cdp");
        }
        try {
            com.fasterxml.jackson.databind.node.ObjectNode json = dev.domengine.snapshot.CdpSnapshotProducer.produce(session, flags);
            return CliResult.ok(Jsons.pretty(json));
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
            case "process", "headed", "i-accept-local-attach", "follow", "stdio", "strict", "explain", "geometry", "count-only", "append",
                    "submit", "all-frames", "describe", "auto-wait", "promote-visual", "mobile", "verify", "perform", "scroll", "live",
                    "force-hover", "refind", "full-page" -> true;
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
        if (text.contains("stale")) return "stale";
        if (text.contains("not found") || text.contains("no page target")) return "not_found";
        if (text.contains("ambiguous")) return "ambiguous";
        if (text.contains("invalid")) return "invalid";
        if (text.contains("timed out") || text.contains("timeout")) return "timeout";
        if (text.contains("needs --id") || text.contains("opt-in") || text.contains("does not run inside")) return "invalid";
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
                session create|list|attach|close|join|log [--session-dir DIR]
                session join --id ID | --cdp URL [--i-accept-local-attach]
                session log --id ID [--follow]
                attach --cdp URL --i-accept-local-attach
                snapshot --session ID | --cdp URL --i-accept-local-attach [--full-page] [--out-dir DIR]
                premap --file page.html [--screenshot shot.png] [--out-dir DIR]
                query --file page.html --css SELECTOR [--selector EXPR] [--pipeline EXPR] [--geometry] [--explain] [--viewport file.pgm]
                  EXPR accepts engine=value stages joined by >> and boolean AND, OR, and NOT
                  live xpath is XPath 1.0 via document.evaluate. An invalid expression is an error, not zero hits.
                  relatives: near below above left-of right-of inside after before (left and right are aliases); filter form <relation>(<anchor query>)
                  look: look=color=red or look=opacity>0.5,background-color=#fff (comma AND, pipe OR)
                  example: (css=button AND text="Submit") >> near=50
                click|type|scroll|submit|key|dblclick|contextclick|drag|eval --session ID
                screenshot --session ID [--out file.png]
                observe|wait --file page.html|--session ID --css SELECTOR [--timeout-ms N]
                observe --session ID|--cdp URL [--type KIND] [--url TEXT] [--since MS] [--until MS] [--limit N] [--wait-ms N]
                  no selector: CDP events the session buffered since attach (network, navigation, console, log)
                desktop list|attach|walk|query|resolve|close|click|type|snapshot
                  attach --fixture FILE   replays a recorded tree
                  attach --live [--name WINDOW]   Windows UIA, macOS AX, or Linux AT-SPI
                  snapshot --id ID [--screenshot file.png] [--out-dir DIR]
                  a fixture snapshot needs --screenshot
                  query and resolve use the same --pipeline, --css, --xpath, and --text as browser query
                  frame, shadow, and pierce are browser-only and are rejected on desktop
                  live click and type act on the OS control; a missing bus, permission, or input tool fails
                  click, type, and resolve list candidates when more than one node matches
                query|resolve|info|recommend|validate [file] [--css SELECTOR]
                capture --session ID | --file page.html | --url URL
                conform --root DIR [--live]
                log list|tail|show [--dir DIR] [--file NAME] [--lines N]
                mcp
                serve --stdio
                """.formatted(EngineInfo.VERSION);
    }

    private static boolean acceptsFile(String command) {
        return switch (command) {
            case "query", "resolve", "info", "recommend", "validate", "capture", "premap" -> true;
            default -> false;
        };
    }

    private static CliResult logs(List<String> args) {
        List<String> rest = args.size() <= 1 ? List.of() : args.subList(1, args.size());
        String verb = rest.isEmpty() || rest.get(0).startsWith("--") ? "" : rest.get(0);
        List<String> flagArgs = verb.isEmpty() ? rest : rest.subList(1, rest.size());
        List<Path> dirs = new ArrayList<>();
        String file = null;
        int lines = 40;
        for (int index = 0; index < flagArgs.size(); index++) {
            String token = flagArgs.get(index);
            if (!token.startsWith("--")) return error(2, "usage", "unexpected argument: " + token);
            String name = token.substring(2);
            String value;
            int equals = name.indexOf('=');
            if (equals >= 0) {
                value = name.substring(equals + 1);
                name = name.substring(0, equals);
            } else {
                if (index + 1 >= flagArgs.size() || flagArgs.get(index + 1).startsWith("--")) {
                    return error(2, "usage", "missing value for --" + name);
                }
                value = flagArgs.get(++index);
            }
            switch (name) {
                case "dir" -> dirs.add(Path.of(value));
                case "file" -> file = value;
                case "lines" -> {
                    try {
                        lines = Integer.parseInt(value.trim());
                    } catch (NumberFormatException failure) {
                        return error(2, "usage", "missing value for --lines");
                    }
                }
                default -> {
                    return error(2, "usage", "unknown flag --" + name);
                }
            }
        }
        if (dirs.isEmpty()) dirs.add(EngineLog.directory());
        EngineLog.info("log", verb.isEmpty() ? "usage" : verb);
        try {
            return switch (verb) {
                case "list" -> CliResult.ok(Jsons.pretty(dev.domengine.log.LogDashboard.list(dirs)));
                case "tail" -> CliResult.ok(Jsons.pretty(dev.domengine.log.LogDashboard.tail(dirs, file, lines)));
                case "show" -> CliResult.ok(Jsons.pretty(dev.domengine.log.LogDashboard.show(dirs, file)));
                default -> error(2, "usage", "log expects list, tail, or show");
            };
        } catch (CdpException failure) {
            String message = failure.getMessage() == null ? "not found" : failure.getMessage();
            return error(1, codeFor(message), message);
        } catch (java.io.IOException failure) {
            return error(1, "failed", failure.getMessage() == null ? "failed" : failure.getMessage());
        }
    }

    private static CliResult mcp() {
        dev.domengine.mcp.McpServer.serve(System.in, System.out);
        return CliResult.ok("");
    }

    private static CliResult resolve(Map<String, String> flags) {
        flags.put("strict", "true");
        return query(flags);
    }

    private static CliResult info(Map<String, String> flags) {
        if (!flags.containsKey("file")) return error(2, "usage", "info requires a file");
        try {
            dev.domengine.tree.DomNode root = dev.domengine.query.HtmlTree.parse(
                    java.nio.file.Files.readString(Path.of(flags.get("file"))));
            ObjectNode node = Jsons.object();
            node.put("ok", true);
            node.put("file", flags.get("file"));
            node.put("nodes", root.count());
            return CliResult.ok(Jsons.pretty(node));
        } catch (java.io.IOException failure) {
            return error(1, "failed", "info file: " + failure.getMessage());
        }
    }

    private static CliResult recommend(Map<String, String> flags) {
        String pipeline = dev.domengine.query.Query.pipelineOf(flags);
        if (pipeline == null) return error(2, "usage", "recommend requires a selector");
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("pipeline", pipeline);
        node.put("engine", dev.domengine.query.Query.leadingEngine(pipeline));
        return CliResult.ok(Jsons.pretty(node));
    }

    private static CliResult validate(Map<String, String> flags) {
        String expected = flags.get("expected");
        if (expected == null || expected.isBlank()) return error(2, "usage", "validate requires --expected");
        dev.domengine.tree.DomNode root = rootOf(flags);
        com.fasterxml.jackson.databind.JsonNode found = dev.domengine.query.Query.run(root, flags);
        int count = found.path("nodes").size();
        boolean numeric = expected.chars().allMatch(Character::isDigit);
        boolean pass = numeric
                ? count == Integer.parseInt(expected)
                : found.path("nodes").toString().toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
        if (!pass) throw new CdpException("validate mismatch");
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("count", count);
        node.put("expected", expected);
        return CliResult.ok(Jsons.pretty(node));
    }

    private static CliResult capture(Map<String, String> flags) {
        if (flags.containsKey("session")) return screenshot(flags);
        if (flags.containsKey("file")) return info(flags);
        if (flags.containsKey("url")) {
            BrowserSession session = SessionRegistry.get().create(
                    "cap-" + System.nanoTime(),
                    flags.get("url"),
                    flags.get("cdp"),
                    false,
                    false,
                    flag(flags, "i-accept-local-attach"),
                    null,
                    List.of()
            );
            try {
                return CliResult.ok(Jsons.pretty(Observe.screenshot(session, flags)));
            } finally {
                SessionRegistry.get().close(session.id);
            }
        }
        return error(2, "usage", "capture requires --session, --file, or --url");
    }

    private static CliResult conform(Map<String, String> flags) {
        if (!flags.containsKey("root")) return error(2, "usage", "conform requires --root");
        Path root = Path.of(flags.get("root"));
        ObjectNode result = flag(flags, "live")
                ? dev.domengine.conform.LiveConformance.run(root)
                : dev.domengine.conform.Conformance.run(root);
        return CliResult.ok(Jsons.pretty(result));
    }

    private static dev.domengine.tree.DomNode rootOf(Map<String, String> flags) {
        if (flags.containsKey("file")) {
            try {
                return dev.domengine.query.HtmlTree.parse(java.nio.file.Files.readString(Path.of(flags.get("file"))));
            } catch (java.io.IOException failure) {
                throw new CdpException("query file: " + failure.getMessage());
            }
        }
        if (flags.containsKey("session")) {
            dev.domengine.tree.DomNode root = SessionRegistry.get().require(flags.get("session")).tree().root;
            root.linkParents();
            return root;
        }
        throw new CdpException("query requires --file or --session");
    }
}
