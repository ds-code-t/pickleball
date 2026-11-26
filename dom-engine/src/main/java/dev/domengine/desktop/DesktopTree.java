package dev.domengine.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.premap.BoxCaps;
import dev.domengine.premap.Candidate;
import dev.domengine.premap.OneMatch;
import dev.domengine.premap.OneMatchException;
import dev.domengine.query.Query;
import dev.domengine.snapshot.IdentityChain;
import dev.domengine.tree.DomNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Desktop accessibility tree. {@code --fixture} replays a recorded tree on every OS.
 * {@code --live} reads Windows UI Automation, macOS Accessibility, or Linux AT-SPI
 * and sends click and type to that platform. A missing bus, permission, or input
 * tool throws. The live path does not report success for a recorded tree.
 */
public final class DesktopTree {
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    /** Fixture path and which file a directory sequence has already loaded. */
    private static final ConcurrentHashMap<String, FixtureCursor> FIXTURES = new ConcurrentHashMap<>();
    /** One tree per attached window id while {@code pkb_warm=on}. */
    private static final ConcurrentHashMap<String, DomNode> WINDOW_CACHE = new ConcurrentHashMap<>();
    private static final List<String> CACHE_NOTES = new CopyOnWriteArrayList<>();
    private static volatile long heapUsedForTests = -1;
    private static volatile long heapMaxForTests = -1;

    private record FixtureCursor(Path path, int step) {
    }

    private DesktopTree() {
    }

    public record Session(String id, String backend, DomNode root, boolean live, String platform, String window, long sequence) {
        public Session(String id, String backend, DomNode root, boolean live, String platform, String window) {
            this(id, backend, root, live, platform, window, 0);
        }

        public Session(String id, String backend, DomNode root) {
            this(id, backend, root, false, "", "", 0);
        }

        public Session bump() {
            return new Session(id, backend, root, live, platform, window, sequence + 1);
        }
    }

    public static Session attach(String id, Path fixture) {
        if (fixture == null || (!Files.isRegularFile(fixture) && !Files.isDirectory(fixture))) {
            throw new CdpException("desktop fixture not found");
        }
        dev.domengine.premap.PreMapLookup.forget(id);
        try {
            Path file = fixture;
            if (Files.isDirectory(fixture)) {
                List<Path> files = jsonFiles(fixture);
                if (files.isEmpty()) throw new CdpException("desktop fixture not found");
                file = files.get(0);
            }
            Session session = new Session(id, "fixture", readTree(file));
            SESSIONS.put(id, session);
            FIXTURES.put(id, new FixtureCursor(fixture, 0));
            return session;
        } catch (CdpException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CdpException("desktop fixture: " + failure.getMessage());
        }
    }

    /**
     * Loads the next JSON file in a directory fixture. A single file stays on the tree already loaded.
     * The caller is the second read: a later {@code snap --mode=diff}.
     */
    public static Session reread(String id) {
        Session session = require(id);
        FixtureCursor cursor = FIXTURES.get(id);
        if (cursor == null || session.live || !Files.isDirectory(cursor.path)) return session;
        List<Path> files = jsonFiles(cursor.path);
        int next = cursor.step + 1;
        if (next >= files.size()) return session;
        try {
            Session updated = new Session(
                    session.id, session.backend, readTree(files.get(next)),
                    session.live, session.platform, session.window, session.sequence + 1);
            SESSIONS.put(id, updated);
            FIXTURES.put(id, new FixtureCursor(cursor.path, next));
            return updated;
        } catch (CdpException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CdpException("desktop fixture: " + failure.getMessage());
        }
    }

    private static DomNode readTree(Path file) throws IOException {
        DomNode root = project(Jsons.MAPPER.readTree(Files.readString(file)));
        root.linkParents();
        return root;
    }

    private static List<Path> jsonFiles(Path dir) {
        try (var listed = Files.list(dir)) {
            return listed
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException failure) {
            throw new CdpException("desktop fixture: " + failure.getMessage());
        }
    }

    /** Live attach. {@code windowName} is empty for the whole desktop, or a window/application/dialog name. */
    public static Session attachLive(String id, String windowName) {
        return attachLive(id, windowName, System.getenv());
    }

    /** Live attach with an explicit environment, so the Wayland refusal is testable without a Wayland session. */
    public static Session attachLive(String id, String windowName, java.util.Map<String, String> env) {
        String refusal = NativeDesktop.waylandRefusal(NativeDesktop.family(), env);
        if (refusal != null) {
            EngineLog.info("desktop", "attach refused " + id + " wayland-only session");
            throw new CdpException(refusal);
        }
        String name = windowName == null ? "" : windowName;
        dev.domengine.premap.PreMapLookup.forget(id);
        String platform = NativeDesktop.platformId();
        DomNode root = NativeDesktop.capture(name);
        root.linkParents();
        DomNode.renumber(root);
        Session session = new Session(id, "live", root, true, platform, name);
        SESSIONS.put(id, session);
        WindowsStructureEvents.start(cacheKey(id, name));
        EngineLog.info("desktop", "attach live " + id + " " + platform + (name.isBlank() ? "" : " " + name));
        return session;
    }

    public static Session open(String id) {
        if (id == null || id.isBlank()) return null;
        return SESSIONS.get(id);
    }

    public static Session require(String id) {
        Session session = SESSIONS.get(id);
        if (session == null) throw new CdpException("session not found: " + id);
        return session;
    }

    public static ObjectNode close(String id) {
        Session session = SESSIONS.get(id);
        if (session == null) throw new CdpException("session not found: " + id);
        String key = cacheKey(session.id, session.window);
        WindowsStructureEvents.stop(key);
        structureChanged(key);
        if (SESSIONS.remove(id) == null) throw new CdpException("session not found: " + id);
        FIXTURES.remove(id);
        dev.domengine.premap.PreMapLookup.forget(id);
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("id", id);
        node.put("closed", true);
        return node;
    }

    public static void resetForTests() {
        WindowsStructureEvents.stopAll();
        SESSIONS.clear();
        FIXTURES.clear();
        WINDOW_CACHE.clear();
        CACHE_NOTES.clear();
        heapUsedForTests = -1;
        heapMaxForTests = -1;
        AtSpi.useSessionBusForTests(null);
    }

    /** Tests supply the heap reading the local refuse line uses when ResourceGate is absent. */
    public static void useHeapForTests(long used, long max) {
        heapUsedForTests = used;
        heapMaxForTests = max;
    }

    /** Lines recorded when {@code pkb_engine_log} names {@code gate}. */
    public static List<String> cacheNotesForTests() {
        return List.copyOf(CACHE_NOTES);
    }

    /** Cache and listener key: the window name, or the session id when the name is blank. */
    static String cacheKey(String id, String window) {
        if (window == null || window.isBlank()) return id == null ? "" : id;
        return window;
    }

    /** Drops the cached tree for one window. A structure-changed event calls this. */
    public static void structureChanged(String windowId) {
        if (windowId == null || windowId.isBlank()) return;
        WINDOW_CACHE.remove(windowId);
    }

    /**
     * Returns the cached tree for {@code windowId} when warm is on and the gate allows the store.
     * {@code pkb_warm=off} reads through. A tree that would cross the refuse line is not stored.
     * {@code declaredBytes} below zero means the loaded tree's own size.
     */
    public static DomNode cachedTree(String windowId, long declaredBytes, Supplier<DomNode> load) {
        String key = windowId == null ? "" : windowId;
        if (!warmOn()) {
            note("cache miss");
            return load.get();
        }
        DomNode cached = WINDOW_CACHE.get(key);
        if (cached != null) {
            note("cache hit");
            return cached;
        }
        DomNode fresh = load.get();
        long bytes = declaredBytes >= 0 ? declaredBytes : byteSize(fresh);
        if (!admit(bytes)) {
            note("cache refuse");
            return fresh;
        }
        if (fresh != null && !key.isBlank()) WINDOW_CACHE.put(key, fresh);
        note("cache miss");
        return fresh;
    }

    private static DomNode captureLive(String window) {
        DomNode fresh = NativeDesktop.capture(window);
        fresh.linkParents();
        DomNode.renumber(fresh);
        return fresh;
    }

    private static boolean warmOn() {
        String raw = System.getProperty("pkb_warm");
        if (raw == null || raw.isBlank()) raw = System.getenv("PKB_WARM");
        if (raw == null || raw.isBlank()) return false;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "1" -> true;
            default -> false;
        };
    }

    private static void note(String message) {
        String raw = System.getProperty("pkb_engine_log");
        if (raw == null || raw.isBlank()) raw = System.getenv("PKB_ENGINE_LOG");
        if (raw != null && !raw.isBlank()) {
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if ("all".equals(value) || "on".equals(value) || "true".equals(value) || value.contains("gate")) {
                CACHE_NOTES.add(message);
            }
        }
        try {
            Class<?> log = Class.forName("tools.dscode.engine.EngineLog");
            log.getMethod("log", java.nio.file.Path.class, String.class, String.class, String.class)
                    .invoke(null, java.nio.file.Path.of("").toAbsolutePath(), "gate", "desktop", message);
        } catch (ReflectiveOperationException ignored) {
            // dom-engine tests read cacheNotesForTests. The pickleball log is optional here.
        }
    }

    private static boolean admit(long declaredBytes) {
        try {
            Class<?> gate = Class.forName("tools.dscode.engine.runtime.ResourceGate");
            Object value = gate.getMethod("allowCache", long.class).invoke(null, declaredBytes);
            if (value instanceof Boolean allowed) return allowed;
        } catch (ClassNotFoundException ignored) {
            // The dom-engine test JVM does not load pickleball. The local line matches allowCache.
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
        return localAdmit(declaredBytes);
    }

    private static boolean localAdmit(long declaredBytes) {
        long max = heapMaxForTests >= 0 ? heapMaxForTests : Runtime.getRuntime().maxMemory();
        long used = heapUsedForTests >= 0 ? heapUsedForTests : Math.max(0, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        int refuse = 85;
        String raw = System.getProperty("pkb_heap_refuse_percent");
        if (raw == null || raw.isBlank()) raw = System.getenv("PKB_HEAP_REFUSE_PERCENT");
        if (raw != null && !raw.isBlank()) {
            try {
                refuse = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                refuse = 85;
            }
        }
        if (max <= 0) return true;
        long extra = declaredBytes < 0 ? 0 : declaredBytes;
        if (used > Long.MAX_VALUE - extra) return false;
        return ((used + extra) * 100L) / max < refuse;
    }

    private static long byteSize(DomNode node) {
        if (node == null) return 0;
        long bytes = 128;
        if (node.children != null) {
            for (DomNode child : node.children) bytes += byteSize(child);
        }
        return bytes;
    }

    public static DomNode project(JsonNode json) {
        DomNode node = new DomNode();
        node.nodeType = 1;
        node.localName = json.path("tag").asText("desktop");
        node.nodeName = node.localName.toUpperCase();
        node.role = json.path("role").asText("");
        node.accessibleName = json.path("name").asText("");
        node.nodeValue = json.path("value").asText("");
        if (json.has("x")) {
            node.x = json.path("x").asDouble();
            node.y = json.path("y").asDouble();
            node.frameX = node.x;
            node.frameY = node.y;
            node.width = json.path("width").asDouble();
            node.height = json.path("height").asDouble();
            node.hasBox = node.width > 0 && node.height > 0;
        }
        copyFixture(json, node, "automationId", "automation-id");
        copyFixture(json, node, "automation-id", "automation-id");
        copyFixture(json, node, "className", "class-name");
        copyFixture(json, node, "class-name", "class-name");
        copyFixture(json, node, "helpText", "help-text");
        copyFixture(json, node, "help-text", "help-text");
        copyFixture(json, node, "platformRole", "platform-role");
        copyFixture(json, node, "platform-role", "platform-role");
        copyFixture(json, node, "text", "text");
        copyFixture(json, node, "uia-runtime-id", "uia-runtime-id");
        copyFixture(json, node, "ax-path", "ax-path");
        copyFixture(json, node, "atspi-path", "atspi-path");
        copyFixture(json, node, "backend", "desktop-backend");
        JsonNode extra = json.get("attributes");
        if (extra != null && extra.isObject()) {
            extra.fields().forEachRemaining(entry -> node.attributes.put(entry.getKey(), entry.getValue().asText("")));
        }
        JsonNode states = json.get("states");
        if (states != null && states.isArray()) {
            for (JsonNode state : states) {
                String name = state.asText("");
                if (name.isBlank() || node.states.contains(name)) continue;
                node.states.add(name);
                if ("offscreen".equals(name) || "hidden".equals(name)) node.displayed = false;
            }
        }
        node.backendNodeId = json.path("id").asLong(0);
        JsonNode children = json.get("children");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) node.children.add(project(child));
        }
        if (node.backendNodeId == 0) {
            node.backendNodeId = 1;
            renumber(node, new long[] {1});
        }
        return node;
    }

    private static void copyFixture(JsonNode json, DomNode node, String from, String to) {
        if (!json.has(from) || json.get(from).isNull()) return;
        String value = json.get(from).asText("");
        if (!value.isBlank()) node.attributes.put(to, value);
    }

    private static void renumber(DomNode node, long[] next) {
        node.backendNodeId = next[0];
        node.nodeId = next[0];
        next[0]++;
        for (DomNode child : node.children) renumber(child, next);
    }

    public static final List<String> VERBS = List.of(
            "list", "attach", "walk", "query", "resolve", "close", "click", "type", "snapshot",
            "launch", "screenshot"
    );

    public static ObjectNode list() {
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        boolean anyLive = false;
        boolean anyFixture = false;
        for (Session session : SESSIONS.values()) {
            if (session.live) anyLive = true;
            else anyFixture = true;
        }
        String backend = "fixture";
        if (anyLive && anyFixture) backend = "mixed";
        else if (anyLive) backend = "live";
        node.put("backend", backend);
        ArrayNode sessions = node.putArray("sessions");
        for (Session session : SESSIONS.values()) {
            ObjectNode one = sessions.addObject();
            one.put("id", session.id);
            one.put("backend", session.backend);
            one.put("live", session.live);
        }
        return node;
    }

    public static ObjectNode walk(String id) {
        Session session = require(id);
        if (session.live) {
            String window = cacheKey(session.id, session.window);
            String captureName = session.window;
            DomNode fresh = warmOn()
                    ? cachedTree(window, -1, () -> captureLive(captureName))
                    : captureLive(captureName);
            session = new Session(session.id, "live", fresh, true, session.platform, session.window, session.sequence + 1);
            SESSIONS.put(id, session);
        } else {
            session = session.bump();
            SESSIONS.put(id, session);
        }
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("id", session.id);
        node.put("backend", session.backend);
        node.put("live", session.live);
        node.set("tree", session.root.toJson());
        EngineLog.info("desktop", "walk " + id);
        return node;
    }

    public static ObjectNode query(String id, Map<String, String> flags) {
        rejectBrowserOnly(flags);
        Session session = require(id);
        if (flags != null && flags.containsKey("identity")) {
            ObjectNode found = IdentityChain.replay(session.root, flags.get("identity"));
            found.put("backend", session.backend);
            EngineLog.info("desktop", "query " + id + " identity " + found.path("matched").asText(""));
            return found;
        }
        ObjectNode found = Query.run(session.root, withSession(session, flags));
        found.put("backend", session.backend);
        EngineLog.info("desktop", "query " + id);
        return found;
    }

    public static DomNode one(String id, Map<String, String> flags) {
        return select(id, flags, true).node;
    }

    public static ObjectNode resolve(String id, Map<String, String> flags) {
        Selected selected = select(id, flags, false);
        ObjectNode found = Query.run(selected.session.root, withSession(selected.session, flags));
        found.put("backend", selected.session.backend);
        found.set("node", selected.node.toJson());
        if (selected.resolved.explain() != null && !selected.resolved.explain().isBlank()) {
            found.put("explain_match", selected.resolved.explain());
        }
        EngineLog.info("desktop", "resolve " + id);
        return found;
    }

    public static ObjectNode click(String id, Map<String, String> flags) {
        Session session = require(id);
        return click(session, one(id, flags));
    }

    public static ObjectNode type(String id, Map<String, String> flags) {
        Session session = require(id);
        DomNode node = nodeByBackend(session, flags);
        if (node == null) node = one(id, flags);
        String text = flags.getOrDefault("text", "");
        boolean append = "true".equals(flags.get("append"));
        if (session.live) NativeDesktop.type(node, text, append);
        store(session.bump());
        node.nodeValue = append ? (node.nodeValue == null ? "" : node.nodeValue) + text : text;
        ObjectNode result = Jsons.object();
        result.put("ok", true);
        result.put("backend", session.backend);
        result.put("verb", "type");
        result.put("name", node.accessibleName);
        result.put("text", node.nodeValue);
        EngineLog.info("desktop", "type " + id);
        return result;
    }

    public static ObjectNode snapshot(String id, Map<String, String> flags) {
        return dev.domengine.snapshot.DesktopSnapshotProducer.produce(require(id), flags);
    }

    /** The node {@code act} already resolved. A query is used only when {@code node-id} is absent. */
    private static DomNode nodeByBackend(Session session, Map<String, String> flags) {
        if (session == null || session.root == null || flags == null) return null;
        String raw = flags.get("node-id");
        if (raw == null || raw.isBlank()) return null;
        try {
            return findBackend(session.root, Long.parseLong(raw.trim()));
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static DomNode findBackend(DomNode node, long id) {
        if (node == null) return null;
        if (node.backendNodeId == id) return node;
        if (node.children != null) {
            for (DomNode child : node.children) {
                DomNode found = findBackend(child, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void store(Session session) {
        SESSIONS.put(session.id(), session);
    }

    public static ObjectNode click(Session session, DomNode node) {
        if (session.live) NativeDesktop.click(node);
        store(session.bump());
        ObjectNode result = Jsons.object();
        result.put("ok", true);
        result.put("backend", session.backend);
        result.put("verb", "click");
        result.put("name", node.accessibleName);
        result.put("role", node.role);
        EngineLog.info("desktop", "click " + session.id + " " + node.accessibleName);
        return result;
    }

    private record Selected(Session session, DomNode node, OneMatch.Resolved resolved) {
    }

    private static Selected select(String id, Map<String, String> flags, boolean act) {
        rejectBrowserOnly(flags);
        Session session = require(id);
        Map<String, String> queryFlags = actionQuery(flags);
        boolean pinned = flags != null && flags.get("node-id") != null && !flags.get("node-id").isBlank();
        if (act && !pinned && Query.pipelineOf(queryFlags) == null) {
            throw new CdpException("act requires --query or --ref");
        }
        JsonNode found = Query.run(session.root, withSession(session, queryFlags));
        JsonNode nodes = found.get("nodes");
        double[] viewport = viewport(session.root);
        BoxCaps caps = BoxCaps.fromFlags(flags);
        List<Candidate> candidates = new ArrayList<>();
        if (nodes != null) {
            for (JsonNode hit : nodes) {
                DomNode node = find(session.root, hit.path("handle").asText(""));
                if (node == null) continue;
                candidates.add(Candidate.of(node, score(hit), List.of(), viewport[0], viewport[1], caps));
            }
        }
        Set<String> exempt = askedRoles(flags, queryFlags);
        OneMatch.Policy policy = act
                ? OneMatch.Policy.act(margin(flags), exempt)
                : OneMatch.Policy.resolve(margin(flags), exempt);
        OneMatch.Resolved resolved = OneMatch.resolve(candidates, policy);
        DomNode node = find(session.root, resolved.chosen().handle());
        if (node == null) throw new OneMatchException("not found", resolved.candidates());
        return new Selected(session, node, resolved);
    }

    private static Double score(JsonNode hit) {
        if (hit != null && hit.has("score") && hit.get("score").isNumber()) return hit.get("score").asDouble();
        return null;
    }

    private static double[] viewport(DomNode root) {
        if (root != null && root.hasBox && root.width > 0 && root.height > 0) return new double[] {root.width, root.height};
        return new double[] {1920, 1080};
    }

    private static double margin(Map<String, String> flags) {
        String raw = flags == null ? null : flags.get("one-match-margin");
        if (raw == null || raw.isBlank()) return 0.15;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException failure) {
            throw new CdpException("usage: one-match margin");
        }
    }

    private static Set<String> askedRoles(Map<String, String> flags, Map<String, String> queryFlags) {
        String role = flags == null ? null : flags.get("role");
        if ((role == null || role.isBlank()) && queryFlags != null) {
            String pipeline = queryFlags.getOrDefault("pipeline", "");
            for (String stage : pipeline.split(">>")) {
                String trimmed = stage.trim();
                if (trimmed.startsWith("role=")) {
                    role = trimmed.substring(5).trim();
                    int bracket = role.indexOf('[');
                    if (bracket >= 0) role = role.substring(0, bracket).trim();
                }
            }
        }
        if (role == null || role.isBlank()) return Set.of();
        return Set.of(role);
    }

    /** Type and click payloads are not a query. {@code --text} on the query verb stays a text stage. */
    private static Map<String, String> actionQuery(Map<String, String> flags) {
        Map<String, String> copy = flags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(flags);
        copy.remove("text");
        copy.remove("append");
        copy.remove("value");
        copy.remove("kind");
        return flagsForQuery(copy);
    }

    private static Map<String, String> flagsForQuery(Map<String, String> flags) {
        if (flags == null) return Map.of();
        if (Query.pipelineOf(flags) != null) return flags;
        String role = flags.get("role");
        if (role == null || role.isBlank()) return flags;
        Map<String, String> copy = new LinkedHashMap<>(flags);
        String name = flags.get("name");
        String expr = name == null || name.isBlank() ? role : role + "[name=\"" + name + "\"]";
        copy.put("pipeline", "role=" + expr);
        return copy;
    }

    private static Map<String, String> withSession(Session session, Map<String, String> flags) {
        Map<String, String> copy = new LinkedHashMap<>(flagsForQuery(flags));
        copy.put("session", session.id);
        if (copy.get("doc-version") == null || copy.get("doc-version").isBlank()) {
            copy.put("doc-version", "desktop:" + session.sequence);
        }
        return copy;
    }

    private static DomNode find(DomNode node, String handle) {
        if (handle.equals(node.id())) return node;
        for (DomNode child : node.children) {
            DomNode found = find(child, handle);
            if (found != null) return found;
        }
        return null;
    }

    static void rejectBrowserOnly(Map<String, String> flags) {
        if (flags == null) return;
        String pierce = flags.get("pierce");
        if (pierce != null && !pierce.isBlank() && !"false".equalsIgnoreCase(pierce)) {
            throw new CdpException("pierce is browser-only");
        }
        if ("true".equals(flags.get("all-frames"))) throw new CdpException("frame is browser-only");
        String pipeline = flags.getOrDefault("pipeline", "") + "\n" + flags.getOrDefault("selector", "")
                + "\n" + flags.getOrDefault("expr", "");
        for (String stage : pipeline.split(">>")) {
            String trimmed = stage.trim().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith("frame=") || trimmed.startsWith("shadow=")) {
                throw new CdpException("frame and shadow are browser-only");
            }
        }
    }
}
