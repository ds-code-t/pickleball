package tools.dscode.browser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BidiAdapterTest {
    @Test
    void preloadBindingScreenshotAndSandboxedCall() {
        ScriptedSocket socket = new ScriptedSocket().auto(request -> {
            String method = ScriptedSocket.method(request);
            long id = ScriptedSocket.id(request);
            return switch (method) {
                case "browsingContext.getTree" -> "{\"id\":" + id + ",\"type\":\"success\",\"result\":{"
                        + "\"contexts\":[{\"context\":\"ctx-1\",\"url\":\"https://example.test/ff\","
                        + "\"children\":[{\"context\":\"ctx-2\",\"url\":\"https://example.test/frame\",\"children\":[]}]}]}}";
                case "script.callFunction" -> "{\"id\":" + id + ",\"type\":\"success\",\"result\":{"
                        + "\"type\":\"success\",\"realm\":\"r\",\"result\":{\"type\":\"string\",\"value\":\"sandboxed\"}}}";
                case "browsingContext.captureScreenshot" -> "{\"id\":" + id + ",\"type\":\"success\",\"result\":{"
                        + "\"data\":\"" + Base64.getEncoder().encodeToString("ff".getBytes(StandardCharsets.UTF_8)) + "\"}}";
                default -> "{\"id\":" + id + ",\"type\":\"success\",\"result\":{}}";
            };
        });
        BidiAdapter adapter = new BidiAdapter(socket);

        assertEquals("bidi", adapter.protocol());
        assertEquals("sandboxed", adapter.evaluateIsolated("pkb-snapshot", "() => 1"));
        adapter.addPreload("window.__pkb = 1");
        AtomicReference<Object> payload = new AtomicReference<>();
        adapter.addBinding("pkbEvent", (name, value) -> payload.set(name + ":" + value));
        socket.inbound("{\"type\":\"event\",\"method\":\"script.message\",\"params\":{"
                + "\"channel\":\"pkbEvent\",\"data\":{\"type\":\"string\",\"value\":\"typed\"}}}");
        assertEquals("pkbEvent:typed", payload.get());
        assertEquals("ff", new String(adapter.screenshot(null), StandardCharsets.UTF_8));
        FrameNode tree = adapter.frameTree();
        assertEquals("ctx-1", tree.id());
        assertEquals("ctx-2", tree.children().get(0).id());
        assertEquals("ctx-1", tree.children().get(0).parentId());

        List<String> methods = socket.methods();
        assertTrue(methods.contains("script.callFunction"), methods.toString());
        assertTrue(methods.contains("script.addPreloadScript"), methods.toString());
        assertTrue(methods.contains("session.subscribe"), methods.toString());
        assertTrue(methods.contains("browsingContext.captureScreenshot"), methods.toString());
        String call = socket.sent().stream().filter(line -> line.contains("script.callFunction")).findFirst().orElseThrow();
        assertTrue(call.contains("pkb-snapshot"), call);
        assertTrue(call.contains("sandbox"), call);
    }

    @Test
    void callFunctionObjectExposesOuterHtml() {
        String html = "<html><body><h1 id=\"marker\">pickleball-live-firefox</h1></body></html>";
        ScriptedSocket socket = new ScriptedSocket().auto(request -> {
            String method = ScriptedSocket.method(request);
            long id = ScriptedSocket.id(request);
            if ("browsingContext.getTree".equals(method)) {
                return "{\"id\":" + id + ",\"type\":\"success\",\"result\":{"
                        + "\"contexts\":[{\"context\":\"ctx-1\",\"url\":\"http://127.0.0.1/\",\"children\":[]}]}}";
            }
            if ("script.callFunction".equals(method)) {
                return "{\"id\":" + id + ",\"type\":\"success\",\"result\":{\"type\":\"success\",\"realm\":\"r\",\"result\":{"
                        + "\"type\":\"object\",\"value\":["
                        + "[\"outerHTML\",{\"type\":\"string\",\"value\":" + jsonString(html) + "}],"
                        + "[\"root\",{\"type\":\"object\",\"value\":["
                        + "[\"tag\",{\"type\":\"string\",\"value\":\"html\"}],"
                        + "[\"children\",{\"type\":\"array\",\"value\":["
                        + "{\"type\":\"object\",\"value\":[[\"tag\",{\"type\":\"string\",\"value\":\"h1\"}]]}"
                        + "]}]"
                        + "]}]"
                        + "]}}}";
            }
            return "{\"id\":" + id + ",\"type\":\"success\",\"result\":{}}";
        });
        BidiAdapter adapter = new BidiAdapter(socket, "ctx-1");
        Object value = adapter.evaluateIsolated("pkb-premap", "function () { return document.documentElement; }");
        Map<?, ?> walk = assertInstanceOf(Map.class, value);
        String outer = String.valueOf(walk.get("outerHTML"));
        assertTrue(outer.contains("pickleball-live-firefox"), outer);
        Map<?, ?> root = assertInstanceOf(Map.class, walk.get("root"));
        assertEquals("html", root.get("tag"));
        assertInstanceOf(List.class, root.get("children"));
    }

    private static String jsonString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
