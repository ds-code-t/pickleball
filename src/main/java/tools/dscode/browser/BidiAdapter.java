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
 * Firefox path. WebDriver BiDi over our own socket:
 * {@code script.addPreloadScript}, sandboxed {@code script.callFunction}, and channels.
 */
public final class BidiAdapter implements BrowserFacade {
    private final JsonRpc rpc;
    private final Map<String, BindingListener> bindings = new ConcurrentHashMap<>();
    private volatile String context;
    private volatile boolean subscribed;

    public BidiAdapter(ProtocolSocket socket) {
        this(socket, null);
    }

    public BidiAdapter(ProtocolSocket socket, String browsingContext) {
        this.context = browsingContext;
        this.rpc = new JsonRpc(socket, new JsonRpc.ReplyMatcher() {
            @Override
            public Long id(JsonNode node) {
                JsonNode id = node.get("id");
                if (id == null || !id.canConvertToLong()) return null;
                if ("event".equals(node.path("type").asText(""))) return null;
                return id.asLong();
            }

            @Override
            public String error(JsonNode node) {
                if (!"error".equals(node.path("type").asText(""))) return null;
                String message = node.path("message").asText("");
                String code = node.path("error").asText("");
                return message.isBlank() ? code : code + ": " + message;
            }
        });
        rpc.onEvent(this::onEvent);
    }

    public static BidiAdapter connect(URI websocket) {
        return new BidiAdapter(ProtocolSockets.open(websocket));
    }

    @Override
    public String protocol() {
        return "bidi";
    }

    @Override
    public Object send(String method, Map<String, ?> params) {
        BrowserLog.info("bidi", "send " + method);
        return JsonRpc.value(rpc.call(method, params));
    }

    @Override
    public Object evaluateIsolated(String worldName, String fn, Object... args) {
        if (fn == null) {
            throw new IllegalArgumentException("error: function text is null.\nfix: Pass a function declaration.");
        }
        String world = worldName == null || worldName.isBlank() ? "pkb" : worldName;
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("context", context());
        target.put("sandbox", world);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("functionDeclaration", functionDeclaration(fn));
        params.put("awaitPromise", false);
        params.put("target", target);
        params.put("arguments", arguments(args));
        JsonNode result = rpc.call("script.callFunction", params);
        return remoteValue(result);
    }

    @Override
    public void addPreload(String script) {
        if (script == null) {
            throw new IllegalArgumentException("error: preload script is null.\nfix: Pass the script text.");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("functionDeclaration", functionDeclaration(script));
        params.put("sandbox", "pkb");
        rpc.call("script.addPreloadScript", params);
    }

    @Override
    public void addBinding(String name, BindingListener listener) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("error: binding name is blank.\nfix: Pass the binding name.");
        }
        bindings.put(name, listener);
        if (!subscribed) {
            rpc.call("session.subscribe", Map.of("events", List.of("script.message")));
            subscribed = true;
        }
        String declaration = "(channel) => { const binding = " + quote(name)
                + "; globalThis[binding] = (payload) => channel(payload); }";
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("type", "channel");
        channel.put("value", Map.of("channel", name, "ownership", "root"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("functionDeclaration", declaration);
        params.put("arguments", List.of(channel));
        params.put("sandbox", "pkb-binding");
        rpc.call("script.addPreloadScript", params);
    }

    @Override
    public byte[] screenshot(Map<String, ?> options) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("context", context());
        Object format = options == null ? null : options.get("format");
        params.put("format", Map.of("type", format == null ? "png" : String.valueOf(format)));
        JsonNode result = rpc.call("browsingContext.captureScreenshot", params);
        return Base64.getDecoder().decode(result.path("data").asText(""));
    }

    @Override
    public FrameNode frameTree() {
        JsonNode result = rpc.call("browsingContext.getTree", Map.of());
        List<FrameNode> roots = new ArrayList<>();
        for (JsonNode node : result.path("contexts")) roots.add(contexts(node, null));
        if (roots.isEmpty()) return FrameNode.leaf("", null, "");
        if (roots.size() == 1) return roots.get(0);
        return new FrameNode("root", null, "", roots);
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

    private void onEvent(String method, JsonNode params) {
        if (!"script.message".equals(method)) return;
        String name = params.path("channel").asText("");
        BindingListener listener = bindings.get(name);
        if (listener == null) return;
        listener.onEvent(name, remoteValue(params.path("data")));
    }

    private String context() {
        String cached = context;
        if (cached != null && !cached.isBlank()) return cached;
        FrameNode tree = frameTree();
        context = tree.id();
        return tree.id();
    }

    private static FrameNode contexts(JsonNode node, String parent) {
        List<FrameNode> children = new ArrayList<>();
        String id = node.path("context").asText("");
        for (JsonNode child : node.path("children")) children.add(contexts(child, id));
        return new FrameNode(id, parent, node.path("url").asText(""), children);
    }

    /** A BiDi RemoteValue, or a CDP return-by-value node, as a plain map, list, or scalar. */
    public static Object remoteValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String type = node.path("type").asText("");
        if (("success".equals(type) || type.isEmpty()) && node.has("result")) return remoteValue(node.get("result"));
        if ("object".equals(type)) return objectValue(node.get("value"));
        if ("array".equals(type) || "set".equals(type)) return arrayValue(node.get("value"));
        if ("null".equals(type) || "undefined".equals(type)) return null;
        if (node.has("type") && node.has("value")) return JsonRpc.value(node.get("value"));
        if (node.has("result")) return remoteValue(node.get("result"));
        return JsonRpc.value(node);
    }

    /** Object properties are {@code [[key, RemoteValue], ...]}. A CDP value is already a JSON object. */
    private static Object objectValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return new LinkedHashMap<String, Object>();
        if (value.isObject()) return JsonRpc.value(value);
        Map<String, Object> map = new LinkedHashMap<>();
        if (!value.isArray()) return map;
        for (JsonNode pair : value) {
            if (!pair.isArray() || pair.size() < 2) continue;
            JsonNode key = pair.get(0);
            String name = key.isObject() ? key.path("value").asText("") : key.asText("");
            map.put(name, remoteValue(pair.get(1)));
        }
        return map;
    }

    private static Object arrayValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return List.of();
        if (!value.isArray()) return JsonRpc.value(value);
        boolean remote = false;
        for (JsonNode item : value) {
            if (item.isObject() && item.has("type")) {
                remote = true;
                break;
            }
        }
        if (!remote) return JsonRpc.value(value);
        List<Object> list = new ArrayList<>();
        for (JsonNode item : value) list.add(remoteValue(item));
        return list;
    }

    private static List<Map<String, Object>> arguments(Object... args) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (args == null) return values;
        for (Object arg : args) {
            Map<String, Object> local = new LinkedHashMap<>();
            if (arg == null) {
                local.put("type", "undefined");
            } else if (arg instanceof Boolean) {
                local.put("type", "boolean");
                local.put("value", arg);
            } else if (arg instanceof Number) {
                local.put("type", "number");
                local.put("value", arg);
            } else {
                local.put("type", "string");
                local.put("value", String.valueOf(arg));
            }
            values.add(local);
        }
        return values;
    }

    static String functionDeclaration(String script) {
        String trimmed = script.trim();
        if (trimmed.startsWith("function") || trimmed.startsWith("(") || trimmed.startsWith("async ")) return trimmed;
        return "() => {\n" + script + "\n}";
    }

    private static String quote(String name) {
        return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static UnsupportedOperationException directOnly(String op) {
        return new UnsupportedOperationException(
                "error: BidiAdapter does not " + op + " through the library.\n"
                        + "fix: Scripted find, click, and type use SeleniumAdapter or PlaywrightAdapter.");
    }
}
