package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import tools.dscode.browser.RpcChannel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Browser-level CDP connection for the protocol host. Discovers page targets, attaches to each with flatten, keeps
 * the session ids, and runs attach hooks (domain enables, preload scripts, routes) on every new page so state
 * installed once covers tabs opened later.
 */
public final class CdpSession implements AutoCloseable {
    /** One attached target. */
    public static final class Target {
        public final String sessionId;
        public final String targetId;
        public final String type;
        public volatile String url;
        public volatile String parentSession;

        Target(String sessionId, String targetId, String type, String url) {
            this.sessionId = sessionId;
            this.targetId = targetId;
            this.type = type;
            this.url = url;
        }
    }

    private final RpcChannel channel;
    private final String endpoint;
    private final Map<String, Target> bySession = new ConcurrentHashMap<>();
    private final List<Consumer<Target>> hooks = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> attaching = new ConcurrentHashMap<>();
    private volatile boolean autoAttachChildren;
    private volatile boolean attachWorkers;
    /** Page the protocol verbs use. Map iteration order must not replace it. */
    private volatile String protocolPageId;
    /** Last tab this session activated, or the page left in front when a new tab stays in the background. */
    private volatile String visiblePageId;

    private CdpSession(RpcChannel channel, String endpoint) {
        this.channel = channel;
        this.endpoint = endpoint;
    }

    public static CdpSession connect(String endpoint) {
        String ws = browserWebSocket(endpoint);
        CdpSession session = new CdpSession(RpcChannel.open(URI.create(ws), RpcChannel.Dialect.CDP).timeoutMs(15_000), ws);
        session.start();
        return session;
    }

    /** http://host:port to the browser ws URL; a ws URL is returned as is. */
    public static String browserWebSocket(String endpoint) {
        String trimmed = endpoint.trim();
        if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) return trimmed;
        try {
            String base = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                    .send(HttpRequest.newBuilder(URI.create(base + "/json/version")).timeout(Duration.ofSeconds(5)).build(),
                            HttpResponse.BodyHandlers.ofString());
            return ProtoJson.MAPPER.readTree(response.body()).path("webSocketDebuggerUrl").asText();
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("error: no DevTools endpoint at " + endpoint + "\nfix: start or attach a browser (doctor --attach)", failure);
        }
    }

    private void start() {
        channel.listen((method, params, sessionId) -> {
            switch (method) {
                case "Target.targetCreated" -> {
                    JsonNode info = params.path("targetInfo");
                    String type = info.path("type").asText();
                    if ("page".equals(type) || (autoAttachChildren && "iframe".equals(type)) || (attachWorkers && worker(info))) {
                        attach(info.path("targetId").asText());
                    }
                }
                case "Target.attachedToTarget" -> onAttached(params, sessionId);
                case "Target.detachedFromTarget" -> bySession.remove(params.path("sessionId").asText());
                case "Target.targetInfoChanged" -> {
                    JsonNode info = params.path("targetInfo");
                    for (Target target : bySession.values()) {
                        if (target.targetId.equals(info.path("targetId").asText())) target.url = info.path("url").asText(target.url);
                    }
                }
                default -> {
                }
            }
        });
        channel.call("Target.setDiscoverTargets", Map.of("discover", true));
        JsonNode targets = channel.call("Target.getTargets", Map.of());
        for (JsonNode info : targets.path("targetInfos")) {
            if ("page".equals(info.path("type").asText())) attach(info.path("targetId").asText());
        }
    }

    private void attach(String targetId) {
        if (targetId == null || targetId.isBlank() || attaching.putIfAbsent(targetId, true) != null) return;
        try {
            channel.call("Target.attachToTarget", Map.of("targetId", targetId, "flatten", true));
        } catch (RuntimeException failure) {
            attaching.remove(targetId);
        }
    }

    private void onAttached(JsonNode params, String parentSession) {
        JsonNode info = params.path("targetInfo");
        String type = info.path("type").asText("");
        Target target = new Target(params.path("sessionId").asText(), info.path("targetId").asText(), type, info.path("url").asText(""));
        target.parentSession = parentSession;
        bySession.put(target.sessionId, target);
        boolean waiting = params.path("waitingForDebugger").asBoolean(false);
        if (waiting && WORKERS.contains(type)) {
            // A paused worker answers domain enables only once it runs, so a hook would stall the event thread until timeout.
            resume(target);
            waiting = false;
        }
        for (Consumer<Target> hook : hooks) {
            try {
                hook.accept(target);
            } catch (RuntimeException ignored) {
                // A hook that fails on one target does not stop the others.
            }
        }
        if (autoAttachChildren && ("page".equals(type) || "iframe".equals(type))) {
            try {
                channel.call("Target.setAutoAttach", Map.of("autoAttach", true, "waitForDebuggerOnStart", false, "flatten", true), target.sessionId);
            } catch (RuntimeException ignored) {
                // Older targets without auto-attach.
            }
        }
        if (waiting) resume(target);
    }

    private void resume(Target target) {
        try {
            channel.call("Runtime.runIfWaitingForDebugger", Map.of(), target.sessionId);
        } catch (RuntimeException ignored) {
            // Already running.
        }
    }

    /** Runs on every attached target now and later. */
    public void onTarget(Consumer<Target> hook) {
        hooks.add(hook);
        for (Target target : bySession.values()) {
            try {
                hook.accept(target);
            } catch (RuntimeException ignored) {
                // Same as above.
            }
        }
    }

    static final java.util.Set<String> WORKERS = java.util.Set.of("service_worker", "shared_worker", "worker");

    /** A worker of the page under test; the browser's own extension workers are not attached. */
    static boolean worker(JsonNode info) {
        return WORKERS.contains(info.path("type").asText()) && !info.path("url").asText("").startsWith("chrome-extension://");
    }

    /** E10: attach service and shared workers as they appear (and those already running). */
    public void attachWorkers() {
        if (attachWorkers) return;
        attachWorkers = true;
        try {
            for (JsonNode info : channel.call("Target.getTargets", Map.of()).path("targetInfos")) {
                if (worker(info)) attach(info.path("targetId").asText());
            }
        } catch (RuntimeException ignored) {
            // Browser without target listing.
        }
    }

    /** E10: auto-attach workers, service workers and OOPIFs under every page. */
    public void autoAttachChildren() {
        if (!autoAttachChildren) {
            autoAttachChildren = true;
            for (Target target : pages()) {
                try {
                    channel.call("Target.setAutoAttach", Map.of("autoAttach", true, "waitForDebuggerOnStart", false, "flatten", true), target.sessionId);
                } catch (RuntimeException ignored) {
                    // Older targets without auto-attach.
                }
            }
        }
        // A later find must see an iframe that was not a target on the first call. Same-site
        // frames stay in-process and are not listed here; the role scan pierces those documents.
        try {
            for (JsonNode info : channel.call("Target.getTargets", Map.of()).path("targetInfos")) {
                if ("iframe".equals(info.path("type").asText())) attach(info.path("targetId").asText());
            }
        } catch (RuntimeException ignored) {
            // A browser that cannot list targets still attaches children as they appear.
        }
    }

    public RpcChannel channel() {
        return channel;
    }

    public String endpoint() {
        return endpoint;
    }

    public Target target(String sessionId) {
        return sessionId == null ? null : bySession.get(sessionId);
    }

    public List<Target> pages() {
        List<Target> out = new ArrayList<>();
        for (Target target : bySession.values()) if ("page".equals(target.type)) out.add(target);
        return out;
    }

    public List<Target> all() {
        return new ArrayList<>(bySession.values());
    }

    /** The page whose URL contains {@code url}, else the remembered protocol page, else the first non-devtools page. */
    public Target page(String url) {
        long deadline = System.currentTimeMillis() + 3000;
        while (true) {
            if (url == null || url.isBlank()) {
                Target preferred = pageByTargetId(protocolPageId);
                if (preferred != null) return preferred;
            }
            Target fallback = null;
            Target urlHit = null;
            for (Target target : pages()) {
                String u = target.url == null ? "" : target.url;
                if (url != null && !url.isBlank() && u.contains(url)) {
                    urlHit = target;
                    break;
                }
                if (fallback == null && !u.startsWith("devtools://") && !u.startsWith("chrome-extension://")) fallback = target;
            }
            if (urlHit != null) return urlHit;
            if (fallback != null && (url == null || url.isBlank())) {
                if (protocolPageId == null || protocolPageId.isBlank()) {
                    rememberProtocol(fallback.targetId);
                    rememberVisible(fallback.targetId);
                }
                Target preferred = pageByTargetId(protocolPageId);
                return preferred != null ? preferred : fallback;
            }
            if (System.currentTimeMillis() > deadline) {
                if (fallback != null) return fallback;
                throw new IllegalStateException("error: no page target is attached\nfix: open a tab in the browser, or check the endpoint");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("error: interrupted while waiting for a page");
            }
        }
    }

    void rememberProtocol(String targetId) {
        protocolPageId = targetId;
    }

    void rememberVisible(String targetId) {
        visiblePageId = targetId;
    }

    String protocolPageId() {
        return protocolPageId;
    }

    /** Null when either id is unknown or they name the same tab. */
    public static String targetWarning(String protocolId, String visibleId) {
        if (protocolId == null || visibleId == null) return null;
        if (protocolId.isBlank() || visibleId.isBlank() || protocolId.equals(visibleId)) return null;
        return "protocol target " + protocolId + " differs from the visible tab " + visibleId;
    }

    String targetWarning() {
        return targetWarning(protocolPageId, visiblePageId);
    }

    private Target pageByTargetId(String targetId) {
        if (targetId == null || targetId.isBlank()) return null;
        for (Target target : pages()) if (targetId.equals(target.targetId)) return target;
        return null;
    }

    public JsonNode send(String sessionId, String method, Map<String, ?> params) {
        return channel.call(method, params, sessionId);
    }

    /** Browser-target command (no session id). */
    public JsonNode browser(String method, Map<String, ?> params) {
        return channel.call(method, params, null);
    }

    @Override
    public void close() {
        channel.close();
    }
}
