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
import dev.domengine.tree.DomNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Desktop accessibility tree. {@code --fixture} replays a recorded tree on every OS.
 * {@code --live} reads Windows UI Automation, macOS Accessibility, or Linux AT-SPI
 * and sends click and type to that platform. A missing bus, permission, or input
 * tool throws. The live path does not report success for a recorded tree.
 */
public final class DesktopTree {
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();

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
        if (fixture == null || !Files.isRegularFile(fixture)) throw new CdpException("desktop fixture not found");
        try {
            JsonNode json = Jsons.MAPPER.readTree(Files.readString(fixture));
            DomNode root = project(json);
            root.linkParents();
            Session session = new Session(id, "fixture", root);
            SESSIONS.put(id, session);
            return session;
        } catch (CdpException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CdpException("desktop fixture: " + failure.getMessage());
        }
    }

    /** Live attach. {@code windowName} is empty for the whole desktop, or a window/application/dialog name. */
    public static Session attachLive(String id, String windowName) {
        String name = windowName == null ? "" : windowName;
        String platform = NativeDesktop.platformId();
        DomNode root = NativeDesktop.capture(name);
        root.linkParents();
        DomNode.renumber(root);
        Session session = new Session(id, "live", root, true, platform, name);
        SESSIONS.put(id, session);
        EngineLog.info("desktop", "attach live " + id + " " + platform + (name.isBlank() ? "" : " " + name));
        return session;
    }

    public static Session require(String id) {
        Session session = SESSIONS.get(id);
        if (session == null) throw new CdpException("session not found: " + id);
        return session;
    }

    public static ObjectNode close(String id) {
        if (SESSIONS.remove(id) == null) throw new CdpException("session not found: " + id);
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("id", id);
        node.put("closed", true);
        return node;
    }

    public static void resetForTests() {
        SESSIONS.clear();
        AtSpi.useSessionBusForTests(null);
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

    private static void renumber(DomNode node, long[] next) {
        node.backendNodeId = next[0];
        node.nodeId = next[0];
        next[0]++;
        for (DomNode child : node.children) renumber(child, next);
    }

    public static final List<String> VERBS = List.of(
            "list", "attach", "walk", "query", "resolve", "close", "click", "type", "snapshot"
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
            DomNode fresh = NativeDesktop.capture(session.window);
            fresh.linkParents();
            DomNode.renumber(fresh);
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
        ObjectNode found = Query.run(session.root, flagsForQuery(flags));
        found.put("backend", session.backend);
        EngineLog.info("desktop", "query " + id);
        return found;
    }

    public static DomNode one(String id, Map<String, String> flags) {
        return select(id, flags, true).node;
    }

    public static ObjectNode resolve(String id, Map<String, String> flags) {
        Selected selected = select(id, flags, false);
        ObjectNode found = Query.run(selected.session.root, flagsForQuery(flags));
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
        DomNode node = one(id, flags);
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
        Map<String, String> queryFlags = flagsForQuery(flags);
        JsonNode found = Query.run(session.root, queryFlags);
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
