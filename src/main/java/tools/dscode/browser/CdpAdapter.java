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

    @Override
    public String protocol() {
        return "cdp";
    }

    @Override
    public Object send(String method, Map<String, ?> params) {
        BrowserLog.info("cdp", "send " + method);
        return JsonRpc.value(rpc.call(method, params));
    }

    @Override
    public Object evaluateIsolated(String worldName, String fn, Object... args) {
        String world = worldName == null || worldName.isBlank() ? "pkb" : worldName;
        int context = worlds.computeIfAbsent(world, this::createWorld);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("expression", expression(fn, args));
        params.put("contextId", context);
        params.put("returnByValue", true);
        JsonNode result = rpc.call("Runtime.evaluate", params);
        JsonNode remote = result.path("result");
        if (result.path("exceptionDetails").isObject()) {
            throw new IllegalStateException(
                    "error: isolated evaluate failed in " + world + ".\n"
                            + "fix: The function must be an expression or a function declaration.");
        }
        return JsonRpc.value(remote.get("value"));
    }

    @Override
    public void addPreload(String script) {
        if (script == null) {
            throw new IllegalArgumentException("error: preload script is null.\nfix: Pass the script text.");
        }
        rpc.call("Page.addScriptToEvaluateOnNewDocument", Map.of("source", script));
    }

    @Override
    public void addBinding(String name, BindingListener listener) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("error: binding name is blank.\nfix: Pass the binding name.");
        }
        bindings.put(name, listener);
        rpc.call("Runtime.addBinding", Map.of("name", name));
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
