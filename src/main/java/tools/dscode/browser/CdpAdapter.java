package tools.dscode.browser;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chromium protocol over our own WebSocket. This is not Selenium
 * {@code executeCdpCommand} and it does not use the engine's {@code CdpConnection}.
 */
public final class CdpAdapter implements BrowserFacade {
    private final JsonRpc rpc;
    private final Map<String, BindingListener> bindings = new ConcurrentHashMap<>();
    private final Map<String, Integer> worlds = new ConcurrentHashMap<>();
    private volatile String rootFrame;
    private volatile String pageTarget;
    private volatile String pageSession;

    public CdpAdapter(ProtocolSocket socket) {
        this.rpc = new JsonRpc(socket, new JsonRpc.ReplyMatcher() {
            @Override
            public Long id(JsonNode node) {
                JsonNode id = node.get("id");
                if (id == null || id.isNull() || !id.canConvertToLong()) return null;
                return id.asLong();
            }

            @Override
            public String error(JsonNode node) {
                JsonNode error = node.get("error");
                if (error == null || error.isNull()) return null;
                String message = error.path("message").asText("");
                return message.isBlank() ? error.toString() : message;
            }
        });
        rpc.onEvent((method, params) -> {
            if (!"Runtime.bindingCalled".equals(method)) return;
            String name = params.path("name").asText("");
            BindingListener listener = bindings.get(name);
            if (listener != null) listener.onEvent(name, JsonRpc.value(params.get("payload")));
        });
    }

    public static CdpAdapter connect(URI websocket) {
        return new CdpAdapter(ProtocolSockets.open(websocket));
    }

    private volatile String pageEnabledFor;

    @Override
    public String protocol() {
        return "cdp";
    }

    /**
     * Page-domain methods ({@code Overlay}, {@code Page}, {@code Runtime}, {@code DOM})
     * are not valid on the browser socket. {@code Target.attachToTarget} is sent
     * with no session, then later calls carry the flattened {@code sessionId}.
     */
    public void attachPage(String targetId) {
        if (targetId == null || targetId.isBlank()) return;
        if (targetId.equals(pageTarget) && pageSession != null) return;
        synchronized (this) {
            if (targetId.equals(pageTarget) && pageSession != null) return;
            rpc.session(null);
            if (socketIsPage(targetId)) {
                pageTarget = targetId;
                pageSession = "";
                return;
            }
            JsonNode result = rpc.call("Target.attachToTarget", Map.of("targetId", targetId, "flatten", true));
            String session = result.path("sessionId").asText("");
            if (session.isBlank()) {
                throw new IllegalStateException(
                        "error: Target.attachToTarget returned no session.\n"
                                + "fix: Pass the selected page target id.");
            }
            rpc.session(session);
            pageTarget = targetId;
            pageSession = session;
            rootFrame = null;
            worlds.clear();
        }
    }

    /** A page websocket already is the selected tab. A browser websocket is not. */
    private boolean socketIsPage(String targetId) {
        try {
            JsonNode info = rpc.call("Target.getTargetInfo", Map.of());
            JsonNode body = info.path("targetInfo").isObject() ? info.path("targetInfo") : info;
            String type = body.path("type").asText("");
            String id = body.path("targetId").asText("");
            if (id.isBlank()) id = body.path("id").asText("");
            return "page".equals(type) && targetId.equals(id);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    @Override
    public Object send(String method, Map<String, ?> params) {
        BrowserLog.info("cdp", "send " + method);
        return JsonRpc.value(rpc.call(method, params));
    }

    @Override
    public Object evaluateIsolated(String worldName, String fn, Object... args) {
        String world = worldName == null || worldName.isBlank() ? "pkb" : worldName;
        JsonNode result;
        try {
            result = evaluateIn(world, fn, args);
        } catch (RuntimeException failure) {
            // A navigation in the same tab destroys the isolated world. The cached context id is
            // then dead, so the world is created again once for the new document.
            if (!staleContext(failure)) throw failure;
            worlds.remove(world);
            rootFrame = null;
            BrowserLog.info("cdp", "isolated world " + world + " was stale; created again");
            result = evaluateIn(world, fn, args);
        }
        JsonNode remote = result.path("result");
        if (result.path("exceptionDetails").isObject()) {
            JsonNode details = result.path("exceptionDetails");
            String thrown = details.path("exception").path("description").asText(details.path("text").asText(""));
            String first = thrown.lines().findFirst().orElse("");
            throw new IllegalStateException(
                    "error: isolated evaluate failed in " + world + (first.isBlank() ? "" : ": " + first) + ".\n"
                            + "fix: The function must be an expression or a function declaration.");
        }
        return JsonRpc.value(remote.get("value"));
    }

    private JsonNode evaluateIn(String world, String fn, Object... args) {
        int context = worlds.computeIfAbsent(world, this::createWorld);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("expression", expression(fn, args));
        params.put("contextId", context);
        params.put("returnByValue", true);
        return rpc.call("Runtime.evaluate", params);
    }

    static boolean staleContext(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.contains("Cannot find context with specified id")
                    || message.contains("Execution context was destroyed"))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void addPreload(String script) {
        if (script == null) {
            throw new IllegalArgumentException("error: preload script is null.\nfix: Pass the script text.");
        }
        rpc.call("Page.addScriptToEvaluateOnNewDocument", Map.of("source", script));
    }

    @Override
    public String addPreloadIn(String script, String world) {
        if (script == null) {
            throw new IllegalArgumentException("error: preload script is null.\nfix: Pass the script text.");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source", script);
        if (world != null && !world.isBlank()) params.put("worldName", world);
        enablePage();
        JsonNode result = rpc.call("Page.addScriptToEvaluateOnNewDocument", params);
        return result == null ? "" : result.path("identifier").asText("");
    }

    /**
     * Chrome evaluates a {@code worldName} preload only on a session with the Page domain enabled;
     * without it the script is accepted and never runs on the next document.
     */
    private void enablePage() {
        String session = pageSession == null ? "" : pageSession;
        if (session.equals(pageEnabledFor)) return;
        rpc.call("Page.enable", Map.of());
        pageEnabledFor = session;
    }

    @Override
    public void removePreload(String identifier) {
        if (identifier == null || identifier.isBlank()) return;
        rpc.call("Page.removeScriptToEvaluateOnNewDocument", Map.of("identifier", identifier));
    }

    @Override
    public void addBinding(String name, BindingListener listener) {
        addBinding(name, listener, null);
    }

    /** {@code executionContextName} is sent only when it is non-blank, so the two-argument call stays name-only. */
    public void addBinding(String name, BindingListener listener, String executionContextName) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("error: binding name is blank.\nfix: Pass the binding name.");
        }
        bindings.put(name, listener);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        if (executionContextName != null && !executionContextName.isBlank()) {
            params.put("executionContextName", executionContextName);
        }
        rpc.call("Runtime.addBinding", params);
    }

    @Override
    public byte[] screenshot(Map<String, ?> options) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("format", "png");
        if (options != null) params.putAll(options);
        JsonNode result = rpc.call("Page.captureScreenshot", params);
        String data = result.path("data").asText("");
        return Base64.getDecoder().decode(data);
    }

    @Override
    public FrameNode frameTree() {
        JsonNode result = rpc.call("Page.getFrameTree", Map.of());
        return frames(result.path("frameTree"));
    }

    @Override
    public List<String> find(String using, String value) {
        throw directOnly("find");
    }

    @Override
    public void click(String handle) {
        throw directOnly("click");
    }

    @Override
    public void type(String handle, String text) {
        throw directOnly("type");
    }

    public void close() {
        rpc.close();
    }

    private int createWorld(String world) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("frameId", rootFrame());
        params.put("worldName", world);
        params.put("grantUniveralAccess", true);
        JsonNode result = rpc.call("Page.createIsolatedWorld", params);
        return result.path("executionContextId").asInt();
    }

    private String rootFrame() {
        String cached = rootFrame;
        if (cached != null) return cached;
        FrameNode tree = frameTree();
        rootFrame = tree.id();
        return tree.id();
    }

    private static FrameNode frames(JsonNode node) {
        JsonNode frame = node.path("frame");
        List<FrameNode> children = new ArrayList<>();
        for (JsonNode child : node.path("childFrames")) children.add(frames(child));
        String parent = frame.path("parentId").isMissingNode() || frame.path("parentId").isNull()
                ? null : frame.path("parentId").asText();
        return new FrameNode(frame.path("id").asText(""), parent, frame.path("url").asText(""), children);
    }

    static String expression(String fn, Object... args) {
        if (fn == null) {
            throw new IllegalArgumentException("error: function text is null.\nfix: Pass the script source.");
        }
        if (args == null || args.length == 0) return fn;
        try {
            return "(" + fn + ").apply(null, " + JsonRpc.MAPPER.writeValueAsString(args) + ")";
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "error: evaluate arguments are not JSON.\nfix: Pass strings, numbers, booleans, maps, or lists.",
                    failure);
        }
    }

    private static UnsupportedOperationException directOnly(String op) {
        return new UnsupportedOperationException(
                "error: CdpAdapter does not " + op + " through the library.\n"
                        + "fix: Scripted find, click, and type use SeleniumAdapter or PlaywrightAdapter.");
    }
}
