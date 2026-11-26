package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.browser.BrowserLog;
import tools.dscode.common.agentfast.AgentEnvelope;
import tools.dscode.common.agentfast.RuntimeInstall;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One workbench surface session. The file is
 * {@code .pickleball/workbench-ui/sessions/<id>.json}. A new session is clean
 * ({@code inject=off}). The overlay layer id is the agent id.
 */
public final class SpineSession {
    public static final String DEFAULT_AGENT = "agent-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SpineSession() {
    }

    public static Path directory(Path project) {
        Path root = project == null ? Path.of("") : project;
        return root.resolve(".pickleball").resolve("workbench-ui").resolve("sessions");
    }

    /** Creates a clean session and makes it the default. */
    public static String create(Path project, String agent) throws IOException {
        String id = "s" + HexFormat.of().formatHex(random(4));
        String useAgent = agent == null || agent.isBlank() ? DEFAULT_AGENT : agent.trim();
        ObjectNode node = JSON.createObjectNode();
        node.put("id", id);
        node.put("agent", useAgent);
        node.put("inject", false);
        node.put("mutate", "sibling");
        node.put("layer", useAgent);
        node.put("inputBlocked", false);
        node.put("token", HexFormat.of().formatHex(random(16)));
        node.put("overlay", "");
        ObjectNode agents = node.putObject("agents");
        agents.putObject(useAgent).put("layer", useAgent);
        node.putObject("targets");
        write(project, id, node);
        writeDefault(project, id);
        return id;
    }

    public static void end(Path project, String sessionId) throws IOException {
        String id = require(project, sessionId);
        Files.deleteIfExists(file(project, id));
        String current = current(project);
        if (id.equals(current)) Files.deleteIfExists(defaultFile(project));
        Path root = project == null ? Path.of("") : project;
        Path ui = root.resolve(".pickleball").resolve("workbench-ui");
        deleteTree(ui.resolve("generated").resolve(id));
        deleteTree(ui.resolve("leases").resolve(id));
    }

    /** The session id, or the missing-session error when that file is absent. */
    public static String required(Path project, String sessionId) {
        return require(project, sessionId);
    }

    public static boolean inputBlocked(Path project, String sessionId) {
        try {
            String id = resolve(project, sessionId);
            if (id == null) return false;
            ObjectNode node = load(project, id);
            return node != null && node.path("inputBlocked").asBoolean(false);
        } catch (IOException failure) {
            return false;
        }
    }

    public static void setInputBlocked(Path project, String sessionId, boolean on) throws IOException {
        String id = require(project, sessionId);
        ObjectNode node = loadRequired(project, id);
        node.put("inputBlocked", on);
        write(project, id, node);
    }

    public static String current(Path project) {
        Path pointer = defaultFile(project);
        if (!Files.isRegularFile(pointer)) return null;
        try {
            String id = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            return id.isBlank() ? null : id;
        } catch (IOException failure) {
            return null;
        }
    }

    /** Blank session id uses the default session. Missing both is null. */
    public static String resolve(Path project, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) return sessionId.trim();
        return current(project);
    }

    public static boolean injectEnabled(Path project, String sessionId) {
        try {
            String id = resolve(project, sessionId);
            if (id == null) return false;
            ObjectNode node = load(project, id);
            return node != null && node.path("inject").asBoolean(false);
        } catch (IOException failure) {
            return false;
        }
    }

    public static void setInject(Path project, String sessionId, boolean on) throws IOException {
        String id = require(project, sessionId);
        ObjectNode node = loadRequired(project, id);
        node.put("inject", on);
        if (on) {
            if (node.path("mutate").asText("").isBlank()) node.put("mutate", "sibling");
            node.put("overlay", "sibling");
        } else {
            node.put("overlay", "");
            clearTargets(node);
        }
        write(project, id, node);
        BrowserLog.info("facade", (on ? "inject on " : "inject off ") + id);
    }

    /**
     * Records one attach of a fixture or live target. {@code injected} stays true
     * only while the session is injected. A second attach keeps that flag.
     */
    public static void attach(Path project, String sessionId, String target) throws IOException {
        String id = require(project, sessionId);
        ObjectNode node = loadRequired(project, id);
        boolean on = node.path("inject").asBoolean(false);
        String name = target == null || target.isBlank() ? "fixture" : target.trim();
        ObjectNode targets = targets(node);
        ObjectNode row = targets.putObject(name);
        int key = name.hashCode();
        row.put("key", key);
        row.put("injected", on);
        if (on) RuntimeInstall.consume(key);
        else RuntimeInstall.forget(key);
        write(project, id, node);
    }

    public static boolean injected(Path project, String sessionId, String target) throws IOException {
        String id = require(project, sessionId);
        ObjectNode node = loadRequired(project, id);
        String name = target == null ? "" : target.trim();
        JsonNode row = node.path("targets").path(name);
        return row.path("injected").asBoolean(false);
    }

    public static Map<String, Object> envelope(Path project, String sessionId, String agent, String op) throws IOException {
        String id = require(project, sessionId);
        String useAgent = agent == null || agent.isBlank() ? DEFAULT_AGENT : agent.trim();
        ObjectNode node = loadRequired(project, id);
        ObjectNode agents = agents(node);
        agents.putObject(useAgent).put("layer", useAgent);
        node.put("agent", useAgent);
        node.put("layer", useAgent);
        write(project, id, node);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("op", op == null || op.isBlank() ? "spine" : op);
        body.put("session", id);
        body.put("agent", useAgent);
        body.put("layer", useAgent);
        body.put("inject", node.path("inject").asBoolean(false));
        body.put("mutate", node.path("mutate").asText("sibling"));
        return AgentEnvelope.apply(body, 0, String.valueOf(body.get("op")), project, id, useAgent);
    }

    private static void clearTargets(ObjectNode node) {
        JsonNode targets = node.get("targets");
        if (!(targets instanceof ObjectNode object)) return;
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            JsonNode value = object.get(name);
            if (value instanceof ObjectNode row) {
                if (row.has("key")) RuntimeInstall.forget(row.get("key").asInt());
                row.put("injected", false);
            }
        }
    }

    private static ObjectNode targets(ObjectNode node) {
        JsonNode existing = node.get("targets");
        if (existing instanceof ObjectNode object) return object;
        return node.putObject("targets");
    }

    private static ObjectNode agents(ObjectNode node) {
        JsonNode existing = node.get("agents");
        if (existing instanceof ObjectNode object) return object;
        return node.putObject("agents");
    }

    private static String require(Path project, String sessionId) {
        String id = resolve(project, sessionId);
        if (id == null || !Files.isRegularFile(file(project, id))) {
            throw new IllegalArgumentException("error: missing session\nfix: spine session new");
        }
        return id;
    }

    private static ObjectNode loadRequired(Path project, String id) throws IOException {
        ObjectNode node = load(project, id);
        if (node == null) throw new IllegalArgumentException("error: missing session\nfix: spine session new");
        return node;
    }

    private static ObjectNode load(Path project, String id) throws IOException {
        Path file = file(project, id);
        if (!Files.isRegularFile(file)) return null;
        JsonNode tree = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        if (!(tree instanceof ObjectNode node)) {
            throw new IOException("error: session file is not an object\nfix: spine session new");
        }
        return node;
    }

    private static void write(Path project, String id, ObjectNode node) throws IOException {
        Path dir = directory(project);
        Files.createDirectories(dir);
        Path file = file(project, id);
        Path temp = dir.resolve(id + ".json.tmp");
        Files.writeString(temp, JSON.writeValueAsString(node), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException failure) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeDefault(Path project, String id) throws IOException {
        Files.createDirectories(directory(project));
        Files.writeString(defaultFile(project), id, StandardCharsets.UTF_8);
    }

    private static Path file(Path project, String id) {
        return directory(project).resolve(id + ".json");
    }

    private static Path defaultFile(Path project) {
        return directory(project).resolve("default");
    }

    private static void deleteTree(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) return;
        try (var walk = Files.walk(directory)) {
            List<Path> paths = walk.sorted((left, right) -> right.compareTo(left)).toList();
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
