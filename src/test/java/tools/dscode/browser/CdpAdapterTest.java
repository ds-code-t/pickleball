package tools.dscode.browser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdpAdapterTest {
    @Test
    void sendEvaluatePreloadBindingScreenshotAndFrames() {
        ScriptedSocket socket = new ScriptedSocket().auto(request -> {
            String method = ScriptedSocket.method(request);
            return switch (method) {
                case "Page.getFrameTree" -> ScriptedSocket.cdp(request, """
                        {"frameTree":{"frame":{"id":"root","url":"https://example.test"},
                        "childFrames":[{"frame":{"id":"child","parentId":"root","url":"https://example.test/ad"}}]}}""");
                case "Page.createIsolatedWorld" -> ScriptedSocket.cdp(request, "{\"executionContextId\":7}");
                case "Runtime.evaluate" -> ScriptedSocket.cdp(request, "{\"result\":{\"type\":\"number\",\"value\":11}}");
                case "Page.captureScreenshot" -> ScriptedSocket.cdp(request,
                        "{\"data\":\"" + Base64.getEncoder().encodeToString("png".getBytes(StandardCharsets.UTF_8)) + "\"}");
                default -> ScriptedSocket.cdp(request, "{}");
            };
        });
        CdpAdapter adapter = new CdpAdapter(socket);

        assertEquals("cdp", adapter.protocol());
        assertTrue(adapter.send("Browser.getVersion", Map.of()) instanceof Map<?, ?>);
        assertEquals(11, adapter.evaluateIsolated("pkb-snapshot", "function(){return 1}"));
        adapter.addPreload("window.__pkb = 1");
        AtomicReference<Object> payload = new AtomicReference<>();
        adapter.addBinding("pkbEvent", (name, value) -> payload.set(name + ":" + value));
        socket.inbound("{\"method\":\"Runtime.bindingCalled\",\"params\":{\"name\":\"pkbEvent\",\"payload\":\"clicked\"}}");
        assertEquals("pkbEvent:clicked", payload.get());
        assertEquals("png", new String(adapter.screenshot(Map.of("fromSurface", true)), StandardCharsets.UTF_8));

        FrameNode tree = adapter.frameTree();
        assertEquals("root", tree.id());
        assertEquals("https://example.test", tree.url());
        assertEquals("child", tree.children().get(0).id());
        assertEquals("root", tree.children().get(0).parentId());

        List<String> methods = socket.methods();
        assertTrue(methods.contains("Page.createIsolatedWorld"), methods.toString());
        assertTrue(methods.contains("Runtime.evaluate"), methods.toString());
        assertTrue(methods.contains("Page.addScriptToEvaluateOnNewDocument"), methods.toString());
        assertTrue(methods.contains("Runtime.addBinding"), methods.toString());
        assertTrue(methods.contains("Page.captureScreenshot"), methods.toString());
        String world = socket.sent().stream()
                .filter(line -> line.contains("Page.createIsolatedWorld"))
                .findFirst()
                .orElseThrow();
        assertTrue(world.contains("pkb-snapshot"), world);
        assertTrue(world.contains("grantUniveralAccess"), world);
    }
}
