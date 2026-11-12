package dev.domengine.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.query.Query;
import dev.domengine.tree.DomNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Desktop accessibility tree. The fixture backend replays a recorded tree on every OS.
 * Native AT-SPI / UIA / AX is not required for the fixture path.
 */
public final class DesktopTree {
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private DesktopTree() {
    }

    public record Session(String id, String backend, DomNode root) {
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
            "list", "attach", "walk", "query", "resolve", "close", "click", "type"
    );

    public static ObjectNode list() {
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("backend", "fixture");
        ArrayNode sessions = node.putArray("sessions");
        for (Session session : SESSIONS.values()) {
            ObjectNode one = sessions.addObject();
            one.put("id", session.id);
            one.put("backend", session.backend);
        }
        return node;
    }

    public static ObjectNode walk(String id) {
        Session session = require(id);
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("id", session.id);
        node.put("backend", session.backend);
        node.set("tree", session.root.toJson());
        EngineLog.info("desktop", "walk " + id);
        return node;
    }

    public static ObjectNode query(String id, Map<String, String> flags) {
        Session session = require(id);
        ObjectNode found = Query.run(session.root, flags);
        found.put("backend", session.backend);
        EngineLog.info("desktop", "query " + id);
        return found;
    }

    public static DomNode one(String id, Map<String, String> flags) {
        Session session = require(id);
        JsonNode found = Query.run(session.root, flags);
        JsonNode nodes = found.get("nodes");
        if (nodes == null || nodes.isEmpty()) throw new CdpException("not found");
        if (nodes.size() > 1) throw new CdpException("ambiguous");
        String handle = nodes.get(0).path("handle").asText("");
        DomNode node = find(session.root, handle);
        if (node == null) throw new CdpException("not found");
        return node;
    }

    public static ObjectNode resolve(String id, Map<String, String> flags) {
        DomNode node = one(id, flags);
        ObjectNode result = Jsons.object();
        result.put("ok", true);
        result.put("backend", require(id).backend);
        result.set("node", node.toJson());
        EngineLog.info("desktop", "resolve " + id);
        return result;
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

    public static ObjectNode click(Session session, DomNode node) {
        ObjectNode result = Jsons.object();
        result.put("ok", true);
        result.put("backend", session.backend);
        result.put("verb", "click");
        result.put("name", node.accessibleName);
        result.put("role", node.role);
        EngineLog.info("desktop", "click " + session.id + " " + node.accessibleName);
        return result;
    }

    private static DomNode find(DomNode node, String handle) {
        if (handle.equals(node.id())) return node;
        for (DomNode child : node.children) {
            DomNode found = find(child, handle);
            if (found != null) return found;
        }
        return null;
    }
}
