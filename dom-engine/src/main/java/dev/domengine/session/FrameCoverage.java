package dev.domengine.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Continuous out-of-process frame attach on one CDP socket.
 * {@code waitForDebuggerOnStart} pauses a new iframe until the preload runs,
 * then {@code Runtime.runIfWaitingForDebugger} releases it. The frame id maps
 * to that child session. Listeners only enqueue; they do not call CDP.
 */
public final class FrameCoverage {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pkb-oopif");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<CdpConnection> ARMED = ConcurrentHashMap.newKeySet();
    /** One socket owns auto-attach. A second attach to the same browser must not pause frames again. */
    private static final AtomicReference<CdpConnection> OWNER = new AtomicReference<>();
    private static final ConcurrentHashMap<CdpConnection, Map<String, String>> BY_FRAME = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CdpConnection, Map<String, String>> BY_SESSION = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CdpConnection, Set<String>> SEEN_TARGETS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CdpConnection, Map<String, String>> URLS = new ConcurrentHashMap<>();

    private FrameCoverage() {
    }

    /** Arms iframe auto-attach once. A second call on the same socket does nothing. */
    public static void arm(CdpConnection connection) {
        if (connection == null || !ARMED.add(connection)) return;
        if (!OWNER.compareAndSet(null, connection)) return;
        connection.addEventListener(message -> onEvent(connection, message));
        ObjectNode auto = Jsons.object();
        auto.put("autoAttach", true);
        auto.put("waitForDebuggerOnStart", true);
        auto.put("flatten", true);
        ArrayNode filter = auto.putArray("filter");
        filter.addObject().put("type", "iframe");
        try {
            connection.call("Target.setAutoAttach", auto, TIMEOUT);
            EngineLog.info("frame", "auto-attach waitForDebuggerOnStart");
        } catch (RuntimeException filtered) {
            auto.remove("filter");
            try {
                connection.call("Target.setAutoAttach", auto, TIMEOUT);
                EngineLog.info("frame", "auto-attach unfiltered " + filtered.getMessage());
            } catch (RuntimeException failure) {
                EngineLog.info("frame", "auto-attach " + failure.getMessage());
            }
        }
    }

    public static boolean armed(CdpConnection connection) {
        return connection != null && ARMED.contains(connection);
    }

    public static void drop(CdpConnection connection) {
        if (connection == null) return;
        ARMED.remove(connection);
        BY_FRAME.remove(connection);
        BY_SESSION.remove(connection);
        SEEN_TARGETS.remove(connection);
        URLS.remove(connection);
        OWNER.compareAndSet(connection, null);
    }

    /** Child session for this frame, or empty when auto-attach has not reported it yet. */
    public static String sessionId(CdpConnection connection, String frameId) {
        if (connection == null || frameId == null || frameId.isBlank()) return "";
        Map<String, String> frames = BY_FRAME.get(connection);
        if (frames == null) return "";
        String session = frames.get(frameId);
        return session == null ? "" : session;
    }

    /** Waits until auto-attach records {@code frameId}, or returns empty at the deadline. */
    public static String awaitSession(CdpConnection connection, String frameId, long waitMs) {
        long deadline = System.nanoTime() + Math.max(0, waitMs) * 1_000_000L;
        String session = sessionId(connection, frameId);
        while (session.isBlank() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            session = sessionId(connection, frameId);
        }
        return session;
    }

    /** Frame ids recorded by auto-attach. */
    public static Set<String> frameIds(CdpConnection connection) {
        Map<String, String> frames = connection == null ? null : BY_FRAME.get(connection);
        if (frames == null || frames.isEmpty()) return Set.of();
        return Set.copyOf(frames.keySet());
    }

    /** Last url seen for a child frame, or empty. */
    public static String url(CdpConnection connection, String frameId) {
        if (connection == null || frameId == null) return "";
        Map<String, String> urls = URLS.get(connection);
        if (urls == null) return "";
        String url = urls.get(frameId);
        return url == null ? "" : url;
    }

    /** The child document attribute written by the page after the preload global. */
    public static String boot(CdpConnection connection, String sessionId) {
        if (connection == null || sessionId == null || sessionId.isBlank()) return "";
        try {
            ObjectNode params = Jsons.object();
            params.put("expression", "document.documentElement && document.documentElement.getAttribute('data-boot') || ''");
            params.put("returnByValue", true);
            JsonNode value = connection.call("Runtime.evaluate", params, sessionId, TIMEOUT);
            return value.path("result").path("value").asText("");
        } catch (RuntimeException failure) {
            EngineLog.info("frame", "boot " + failure.getMessage());
            return "";
        }
    }

    /**
     * Attaches iframe targets that auto-attach did not report yet.
     * A paused frame stays out of the parent frame tree until it is resumed.
     */
    public static void harvest(CdpConnection connection) {
        if (connection == null || !ARMED.contains(connection)) return;
        JsonNode listed;
        try {
            listed = connection.call("Target.getTargets", Jsons.object(), TIMEOUT);
        } catch (RuntimeException failure) {
            EngineLog.info("frame", "targets " + failure.getMessage());
            return;
        }
        JsonNode infos = listed.path("targetInfos");
        if (!infos.isArray()) return;
        Set<String> seen = SEEN_TARGETS.computeIfAbsent(connection, key -> ConcurrentHashMap.newKeySet());
        for (JsonNode info : infos) {
            String type = info.path("type").asText("");
            String url = info.path("url").asText("");
            String targetId = info.path("targetId").asText("");
            if (!targetId.isBlank() && !url.isBlank()) {
                URLS.computeIfAbsent(connection, key -> new ConcurrentHashMap<>()).put(targetId, url);
            }
            if (!"iframe".equals(type) || targetId.isBlank()) continue;
            if (!sessionId(connection, targetId).isBlank()) {
                seen.add(targetId);
                continue;
            }
            if (!seen.add(targetId)) continue;
            try {
                ObjectNode attach = Jsons.object();
                attach.put("targetId", targetId);
                attach.put("flatten", true);
                JsonNode session = connection.call("Target.attachToTarget", attach, TIMEOUT);
                String sessionId = session.path("sessionId").asText("");
                if (sessionId.isBlank()) {
                    seen.remove(targetId);
                    continue;
                }
                armChild(connection, sessionId, true);
            } catch (RuntimeException failure) {
                String message = failure.getMessage() == null ? "" : failure.getMessage();
                if (!message.toLowerCase(java.util.Locale.ROOT).contains("already")) seen.remove(targetId);
                EngineLog.info("frame", "attach " + message);
            }
        }
    }

    private static void onEvent(CdpConnection connection, JsonNode message) {
        String method = message.path("method").asText("");
        if ("Target.targetInfoChanged".equals(method)) {
            JsonNode info = message.path("params").path("targetInfo");
            if ("iframe".equals(info.path("type").asText(""))) {
                String id = info.path("targetId").asText("");
                String url = info.path("url").asText("");
                if (!id.isBlank() && !url.isBlank()) {
                    URLS.computeIfAbsent(connection, key -> new ConcurrentHashMap<>()).put(id, url);
                }
            }
            return;
        }
        if ("Target.detachedFromTarget".equals(method)) {
            String sessionId = message.path("params").path("sessionId").asText("");
            if (!sessionId.isBlank()) EXECUTOR.execute(() -> forget(connection, sessionId));
            return;
        }
        if (!"Target.attachedToTarget".equals(method)) return;
        JsonNode params = message.path("params");
        String sessionId = params.path("sessionId").asText("");
        String type = params.path("targetInfo").path("type").asText("");
        if (sessionId.isBlank() || !"iframe".equals(type)) return;
        boolean waiting = params.path("waitingForDebugger").asBoolean(false);
        EXECUTOR.execute(() -> armChild(connection, sessionId, waiting));
    }

    private static void armChild(CdpConnection connection, String sessionId, boolean waiting) {
        try {
            connection.call("Page.enable", Jsons.object(), sessionId, TIMEOUT);
            ObjectNode preload = Jsons.object();
            preload.put("source", "window.__pkbOopif='before-page'");
            connection.call("Page.addScriptToEvaluateOnNewDocument", preload, sessionId, TIMEOUT);
            ObjectNode mark = Jsons.object();
            mark.put("expression", "window.__pkbOopif='before-page'");
            mark.put("returnByValue", true);
            connection.call("Runtime.evaluate", mark, sessionId, TIMEOUT);
            JsonNode tree = connection.call("Page.getFrameTree", Jsons.object(), sessionId, TIMEOUT);
            String frameId = tree.path("frameTree").path("frame").path("id").asText("");
            if (!frameId.isBlank()) {
                remember(connection, frameId, sessionId);
                String url = tree.path("frameTree").path("frame").path("url").asText("");
                if (!url.isBlank()) URLS.computeIfAbsent(connection, key -> new ConcurrentHashMap<>()).put(frameId, url);
            }
            try {
                ObjectNode world = Jsons.object();
                if (!frameId.isBlank()) world.put("frameId", frameId);
                world.put("worldName", "pkb-surface");
                world.put("grantUniveralAccess", true);
                connection.call("Page.createIsolatedWorld", world, sessionId, TIMEOUT);
            } catch (RuntimeException ignored) {
                EngineLog.info("frame", "pkb world skipped");
            }
            try {
                ObjectNode binding = Jsons.object();
                binding.put("name", "pkbSurface");
                binding.put("executionContextName", "pkb-surface");
                connection.call("Runtime.addBinding", binding, sessionId, TIMEOUT);
            } catch (RuntimeException ignored) {
                EngineLog.info("frame", "binding skipped");
            }
        } catch (RuntimeException failure) {
            EngineLog.info("frame", "arm " + failure.getMessage());
        } finally {
            if (waiting) {
                try {
                    connection.call("Runtime.runIfWaitingForDebugger", Jsons.object(), sessionId, TIMEOUT);
                } catch (RuntimeException failure) {
                    EngineLog.info("frame", "resume " + failure.getMessage());
                }
            }
        }
    }

    private static void remember(CdpConnection connection, String frameId, String sessionId) {
        BY_FRAME.computeIfAbsent(connection, key -> new ConcurrentHashMap<>()).put(frameId, sessionId);
        BY_SESSION.computeIfAbsent(connection, key -> new ConcurrentHashMap<>()).put(sessionId, frameId);
    }

    private static void forget(CdpConnection connection, String sessionId) {
        Map<String, String> sessions = BY_SESSION.get(connection);
        if (sessions == null) return;
        String frameId = sessions.remove(sessionId);
        Map<String, String> frames = BY_FRAME.get(connection);
        if (frames != null && frameId != null) frames.remove(frameId, sessionId);
    }
}
