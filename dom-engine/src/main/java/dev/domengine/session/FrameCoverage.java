package dev.domengine.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    private static final ConcurrentHashMap<CdpConnection, Map<String, String>> PARENTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CdpConnection, Map<String, String>> SURFACE = new ConcurrentHashMap<>();
    /** Isolated-world context ids, keyed by {@code s:} session and {@code f:} frame. */
    private static final ConcurrentHashMap<CdpConnection, Map<String, Integer>> CONTEXTS = new ConcurrentHashMap<>();
    private static volatile String SURFACE_RUNTIME;

    private FrameCoverage() {
    }

    /** Arms iframe auto-attach once. A second call on the same socket does nothing. */
    public static void arm(CdpConnection connection) {
        if (connection == null || !ARMED.add(connection)) return;
        if (!OWNER.compareAndSet(null, connection)) return;
        connection.addEventListener(message -> onEvent(connection, message));
        try {
            connection.call("Page.enable", Jsons.object(), TIMEOUT);
        } catch (RuntimeException failure) {
            EngineLog.info("frame", "page " + failure.getMessage());
        }
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
        PARENTS.remove(connection);
        SURFACE.remove(connection);
        CONTEXTS.remove(connection);
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

    /**
     * Last url seen for a child frame, or empty.
     * A paused OOPIF reports a blank url until navigation commits, so a blank
     * entry is refreshed from that session's frame tree.
     */
    public static String url(CdpConnection connection, String frameId) {
        if (connection == null || frameId == null) return "";
        String known = storedUrl(connection, frameId);
        if (!known.isBlank()) return known;
        String session = sessionId(connection, frameId);
        if (!session.isBlank()) refreshUrls(connection, session);
        return storedUrl(connection, frameId);
    }

    private static String storedUrl(CdpConnection connection, String frameId) {
        Map<String, String> urls = URLS.get(connection);
        if (urls == null) return "";
        String url = urls.get(frameId);
        return url == null ? "" : url;
    }

    /** Stores http(s) urls from a child session's frame tree, including nested frames. */
    private static void refreshUrls(CdpConnection connection, String sessionId) {
        if (connection == null || sessionId == null || sessionId.isBlank()) return;
        try {
            JsonNode tree = connection.call("Page.getFrameTree", Jsons.object(), sessionId, Duration.ofSeconds(2));
            storeTree(connection, tree.path("frameTree"), "");
        } catch (RuntimeException failure) {
            EngineLog.info("frame", "url " + failure.getMessage());
        }
    }

    private static void storeTree(CdpConnection connection, JsonNode node, String parent) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        JsonNode frame = node.path("frame");
        String id = frame.path("id").asText("");
        String url = frame.path("url").asText("");
        if (!id.isBlank()) {
            if (!url.isBlank() && !"about:blank".equals(url)) {
                URLS.computeIfAbsent(connection, key -> new ConcurrentHashMap<>()).put(id, url);
            }
            String reported = frame.path("parentId").asText("");
            String parentId = !reported.isBlank() ? reported : parent;
            if (!parentId.isBlank()) {
                PARENTS.computeIfAbsent(connection, key -> new ConcurrentHashMap<>()).put(id, parentId);
            }
        }
        JsonNode children = node.path("childFrames");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) storeTree(connection, child, id);
        }
    }

    /** Frame id remembered for this child session, or empty. */
    public static String frameForSession(CdpConnection connection, String sessionId) {
        if (connection == null || sessionId == null || sessionId.isBlank()) return "";
        Map<String, String> sessions = BY_SESSION.get(connection);
        if (sessions == null) return "";
        String frameId = sessions.get(sessionId);
        return frameId == null ? "" : frameId;
    }

    /** Parent frame id from {@code Page.frameAttached}, or empty. */
    public static String parentOf(CdpConnection connection, String frameId) {
        if (connection == null || frameId == null || frameId.isBlank()) return "";
        Map<String, String> parents = PARENTS.get(connection);
        if (parents == null) return "";
        String parent = parents.get(frameId);
        return parent == null ? "" : parent;
    }

    /**
     * {@code before-page} when the surface runtime was in the pkb world before the page script ran.
     * Empty when that check did not pass.
     */
    public static String surface(CdpConnection connection, String frameId) {
        if (connection == null || frameId == null || frameId.isBlank()) return "";
        Map<String, String> surfaces = SURFACE.get(connection);
        if (surfaces == null) return "";
        String value = surfaces.get(frameId);
        return value == null ? "" : value;
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
        if ("Runtime.executionContextCreated".equals(method)) {
            JsonNode context = message.path("params").path("context");
            if (!"pkb-surface".equals(context.path("name").asText(""))) return;
            int id = context.path("id").asInt(0);
            if (id <= 0) return;
            Map<String, Integer> contexts = CONTEXTS.computeIfAbsent(connection, key -> new ConcurrentHashMap<>());
            String sessionId = message.path("sessionId").asText("");
            if (!sessionId.isBlank()) contexts.put("s:" + sessionId, id);
            String frame = context.path("auxData").path("frameId").asText("");
            if (!frame.isBlank()) contexts.put("f:" + frame, id);
            return;
        }
        if ("Page.frameAttached".equals(method)) {
            String id = message.path("params").path("frameId").asText("");
            String parent = message.path("params").path("parentFrameId").asText("");
            if (!id.isBlank() && !parent.isBlank()) {
                PARENTS.computeIfAbsent(connection, key -> new ConcurrentHashMap<>()).put(id, parent);
            }
            return;
        }
        if ("Page.frameNavigated".equals(method)) {
            JsonNode frame = message.path("params").path("frame");
            String id = frame.path("id").asText("");
            String url = frame.path("url").asText("");
            if (!id.isBlank() && !url.isBlank()) {
                URLS.computeIfAbsent(connection, key -> new ConcurrentHashMap<>()).put(id, url);
            }
            return;
        }
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
        String frameId = "";
        String runtime = "";
        try {
            connection.call("Page.enable", Jsons.object(), sessionId, TIMEOUT);
            try {
                connection.call("Runtime.enable", Jsons.object(), sessionId, TIMEOUT);
            } catch (RuntimeException ignored) {
                EngineLog.info("frame", "runtime enable skipped");
            }
            runtime = surfaceRuntime();
            if (!runtime.isBlank()) {
                ObjectNode preload = Jsons.object();
                // The trailer records whether this isolated script ran before the page script.
                // Page.createIsolatedWorld hangs while the target is paused, so the preload
                // carries that fact and the probe reads it after resume.
                preload.put("source", runtime + BOOT_TRAILER);
                preload.put("worldName", "pkb-surface");
                connection.call("Page.addScriptToEvaluateOnNewDocument", preload, sessionId, Duration.ofSeconds(15));
            }
            JsonNode tree = connection.call("Page.getFrameTree", Jsons.object(), sessionId, TIMEOUT);
            frameId = tree.path("frameTree").path("frame").path("id").asText("");
            if (!frameId.isBlank()) remember(connection, frameId, sessionId);
            storeTree(connection, tree.path("frameTree"), "");
            try {
                ObjectNode binding = Jsons.object();
                binding.put("name", "pkbSurface");
                binding.put("executionContextName", "pkb-surface");
                connection.call("Runtime.addBinding", binding, sessionId, TIMEOUT);
            } catch (RuntimeException ignored) {
                EngineLog.info("frame", "binding skipped");
            }
            // Nested cross-site frames live in this child target. Auto-attach has to be armed
            // here, before the page is resumed, or the grandchild is never paused or grafted.
            try {
                ObjectNode nested = Jsons.object();
                nested.put("autoAttach", true);
                nested.put("waitForDebuggerOnStart", true);
                nested.put("flatten", true);
                ArrayNode nestedFilter = nested.putArray("filter");
                nestedFilter.addObject().put("type", "iframe");
                connection.call("Target.setAutoAttach", nested, sessionId, TIMEOUT);
            } catch (RuntimeException ignored) {
                EngineLog.info("frame", "nested auto-attach skipped");
            }
        } catch (RuntimeException failure) {
            EngineLog.info("frame", "arm " + failure.getMessage());
        } finally {
            // Resume even when the attach event omitted waitingForDebugger.
            // A paused OOPIF never commits its url, so the parent tree stays blank.
            if (waiting) EngineLog.info("frame", "resume " + sessionId);
            try {
                connection.call("Runtime.runIfWaitingForDebugger", Jsons.object(), sessionId, TIMEOUT);
            } catch (RuntimeException failure) {
                EngineLog.info("frame", "resume " + failure.getMessage());
            }
        }
        if (frameId.isBlank()) return;
        refreshUrls(connection, sessionId);
        if (runtime.isBlank()) return;
        String probed = probeSurface(connection, sessionId, frameId);
        if ("before-page".equals(probed)) {
            SURFACE.computeIfAbsent(connection, key -> new ConcurrentHashMap<>()).put(frameId, "before-page");
        }
    }

    /** Set in the pkb-surface world by the preload, before the page's own script. */
    private static final String BOOT_TRAILER =
            "\n;try{globalThis.__pkbBoot=(document.documentElement&&document.documentElement.dataset"
                    + "&&document.documentElement.dataset.ran==='page')?'late':'before-page';}"
                    + "catch(e){globalThis.__pkbBoot='err';}";

    /**
     * Reads {@code __pkbUi} and the preload's boot mark from the pkb-surface world.
     * The mark is taken when the preload runs, so a later page script cannot hide it.
     */
    private static String probeSurface(CdpConnection connection, String sessionId, String frameId) {
        long deadline = System.nanoTime() + 3_000_000_000L;
        long openAt = deadline - 1_000_000_000L;
        String last = "no-context";
        boolean opened = false;
        while (System.nanoTime() < deadline) {
            refreshUrls(connection, sessionId);
            int contextId = isolatedContext(connection, sessionId, frameId);
            if (contextId <= 0 && !opened && System.nanoTime() >= openAt) {
                awaitReady(connection, sessionId);
                contextId = openSurfaceWorld(connection, sessionId, frameId);
                opened = true;
            }
            if (contextId <= 0) {
                sleepBriefly();
                continue;
            }
            last = readBoot(connection, sessionId, contextId);
            if ("before-page".equals(last)) return last;
            if (last.startsWith("boot=") && last.length() > "boot=".length()) return last;
            sleepBriefly();
        }
        return last;
    }

    private static int isolatedContext(CdpConnection connection, String sessionId, String frameId) {
        Map<String, Integer> contexts = CONTEXTS.get(connection);
        if (contexts == null) return 0;
        if (sessionId != null && !sessionId.isBlank()) {
            Integer bySession = contexts.get("s:" + sessionId);
            if (bySession != null && bySession > 0) return bySession;
        }
        if (frameId != null && !frameId.isBlank()) {
            Integer byFrame = contexts.get("f:" + frameId);
            if (byFrame != null && byFrame > 0) return byFrame;
        }
        return 0;
    }

    /** Opens the pkb-surface world after the document has left the debugger pause. */
    private static int openSurfaceWorld(CdpConnection connection, String sessionId, String frameId) {
        try {
            String id = frameId;
            JsonNode tree = connection.call("Page.getFrameTree", Jsons.object(), sessionId, Duration.ofSeconds(2));
            String fresh = tree.path("frameTree").path("frame").path("id").asText("");
            if (!fresh.isBlank()) id = fresh;
            storeTree(connection, tree.path("frameTree"), "");
            if (id == null || id.isBlank()) return 0;
            ObjectNode world = Jsons.object();
            world.put("frameId", id);
            world.put("worldName", "pkb-surface");
            world.put("grantUniveralAccess", true);
            JsonNode created = connection.call("Page.createIsolatedWorld", world, sessionId, Duration.ofSeconds(2));
            return created.path("executionContextId").asInt(0);
        } catch (RuntimeException failure) {
            EngineLog.info("frame", "world " + failure.getMessage());
            return 0;
        }
    }

    private static void awaitReady(CdpConnection connection, String sessionId) {
        try {
            ObjectNode ready = Jsons.object();
            ready.put("expression", "document.readyState");
            ready.put("returnByValue", true);
            JsonNode state = connection.call("Runtime.evaluate", ready, sessionId, Duration.ofSeconds(1));
            String value = state.path("result").path("value").asText("");
            if ("complete".equals(value) || "interactive".equals(value)) return;
        } catch (RuntimeException failure) {
            EngineLog.info("frame", "ready " + failure.getMessage());
        }
    }

    private static String readBoot(CdpConnection connection, String sessionId, int contextId) {
        try {
            ObjectNode kind = Jsons.object();
            kind.put("expression", "({ui:typeof globalThis.__pkbUi,boot:String(globalThis.__pkbBoot||'')})");
            kind.put("returnByValue", true);
            kind.put("contextId", contextId);
            JsonNode value = connection.call("Runtime.evaluate", kind, sessionId, TIMEOUT).path("result").path("value");
            String ui = value.path("ui").asText("");
            String boot = value.path("boot").asText("");
            if (!"object".equals(ui)) return "ui=" + ui + " boot=" + boot;
            return "before-page".equals(boot) ? "before-page" : "boot=" + boot;
        } catch (RuntimeException failure) {
            EngineLog.info("frame", "surface " + failure.getMessage());
            return failure.getMessage() == null ? "err" : failure.getMessage();
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String surfaceRuntime() {
        String cached = SURFACE_RUNTIME;
        if (cached != null) return cached;
        String script = readResource("/tools/dscode/browser/surface/pkb-runtime.js");
        if (script == null || script.isBlank()) {
            SURFACE_RUNTIME = "";
            return "";
        }
        String css = readResource("/tools/dscode/browser/surface/panel.css");
        if (css != null) script = script.replace("\"__PKB_PANEL_CSS__\"", jsQuote(css));
        SURFACE_RUNTIME = script;
        return script;
    }

    private static String readResource(String path) {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        try (InputStream in = openResource(path, relative)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            EngineLog.info("frame", "runtime " + failure.getMessage());
            return null;
        }
    }

    /** Classpath first, then the built resource directory the host is launched from. */
    private static InputStream openResource(String path, String relative) throws java.io.IOException {
        InputStream in = FrameCoverage.class.getResourceAsStream(path);
        if (in != null) return in;
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            in = context.getResourceAsStream(relative);
            if (in != null) return in;
        }
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            java.nio.file.Path file = java.nio.file.Path.of(entry).resolve(relative);
            if (java.nio.file.Files.isRegularFile(file)) return java.nio.file.Files.newInputStream(file);
        }
        for (String root : new String[] {"build/resources/main", "src/main/resources"}) {
            java.nio.file.Path file = java.nio.file.Path.of(root).resolve(relative);
            if (java.nio.file.Files.isRegularFile(file)) return java.nio.file.Files.newInputStream(file);
        }
        return null;
    }

    private static String jsQuote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
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
