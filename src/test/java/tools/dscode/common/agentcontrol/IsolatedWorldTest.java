package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.WebDriver;
import tools.dscode.browser.BrowserLog;
import tools.dscode.browser.ProtocolSelector;
import tools.dscode.browser.ProtocolSockets;
import tools.dscode.browser.ScriptedSocket;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolatedWorldTest {
    @AfterEach
    void clean() {
        PageSurfaces.clearDriver();
        ProtocolSelector.resetForTests();
        ProtocolSockets.resetForTests();
        BrowserLog.resetForTests();
    }

    @Test
    void liveHelperRunsInAnIsolatedWorld() throws Exception {
        AtomicInteger scripts = new AtomicInteger();
        ScriptedSocket socket = new ScriptedSocket().auto(request -> {
            String method = ScriptedSocket.method(request);
            return switch (method) {
                case "Page.getFrameTree" -> ScriptedSocket.cdp(request, """
                        {"frameTree":{"frame":{"id":"root","url":"https://example.test"}}}""");
                case "Page.createIsolatedWorld" -> ScriptedSocket.cdp(request, "{\"executionContextId\":7}");
                case "Runtime.evaluate" -> ScriptedSocket.cdp(request,
                        "{\"result\":{\"type\":\"object\",\"value\":{\"ok\":true}}}");
                default -> ScriptedSocket.cdp(request, "{}");
            };
        });
        ProtocolSockets.useFactoryForTests(uri -> socket);
        ProtocolSelector.useEndpointsForTests(driver -> java.net.URI.create("ws://127.0.0.1:9/devtools/browser"));
        WebDriver driver = driver(scripts);
        PageSurfaces.manage(driver, "chrome-headed-quiet", true);
        Map<String, Object> placed = PageSurfaces.place(null, "append", null, null, "<b>n</b>", "html", null, null, null);
        assertEquals("html", placed.get("kind"));
        assertEquals(0, scripts.get());
        assertTrue(socket.methods().contains("Page.createIsolatedWorld"), socket.methods().toString());
        assertTrue(socket.methods().contains("Page.addScriptToEvaluateOnNewDocument"), socket.methods().toString());
        assertTrue(socket.methods().contains("Runtime.addBinding"), socket.methods().toString());
        assertTrue(socket.methods().contains("Runtime.evaluate"), socket.methods().toString());
        String world = socket.sent().stream().filter(line -> line.contains("Page.createIsolatedWorld")).findFirst().orElseThrow();
        assertTrue(world.contains("pkb-surface"), world);
    }

    @Test
    void pageDomainAttachesToTheSelectedTab() throws Exception {
        ScriptedSocket socket = new ScriptedSocket().auto(request -> {
            String method = ScriptedSocket.method(request);
            return switch (method) {
                case "Target.attachToTarget" -> ScriptedSocket.cdp(request, "{\"sessionId\":\"sess-9\"}");
                case "Page.getFrameTree" -> ScriptedSocket.cdp(request, """
                        {"frameTree":{"frame":{"id":"root","url":"https://example.test"}}}""");
                default -> ScriptedSocket.cdp(request, "{}");
            };
        });
        ProtocolSockets.useFactoryForTests(uri -> socket);
        ProtocolSelector.useEndpointsForTests(driver -> java.net.URI.create("ws://127.0.0.1:9/devtools/browser"));
        WebDriver driver = pageDriver();
        PageSurfaces.manage(driver, "chrome-headed-quiet", true);
        PageSurfaces.bindHandle(driver, "tab", "root:4");
        Map<String, Object> painted = PageSurfaces.highlight(null, "root:4", "yellow", null, null, null, null, null);
        assertEquals("Overlay.highlightNode", painted.get("cdp"));
        String attach = socket.sent().stream().filter(line -> line.contains("Target.attachToTarget")).findFirst().orElseThrow();
        assertTrue(attach.contains("PAGE"), attach);
        assertFalse(attach.contains("sessionId"), attach);
        String highlight = socket.sent().stream().filter(line -> line.contains("Overlay.highlightNode")).findFirst().orElseThrow();
        assertTrue(highlight.contains("sess-9"), highlight);
        assertEquals(1, socket.methods().stream().filter("Target.attachToTarget"::equals).count());
    }

    private static WebDriver driver(AtomicInteger scripts) {
        return (WebDriver) java.lang.reflect.Proxy.newProxyInstance(
                IsolatedWorldTest.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, HasCapabilities.class, JavascriptExecutor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCapabilities" -> {
                        MutableCapabilities caps = new MutableCapabilities();
                        caps.setCapability("browserName", "chrome");
                        yield caps;
                    }
                    case "getWindowHandle" -> "tab";
                    case "getWindowHandles" -> java.util.Set.of("tab");
                    case "executeScript" -> {
                        scripts.incrementAndGet();
                        yield Map.of("ok", true);
                    }
                    case "toString" -> "chrome";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }

    private static WebDriver pageDriver() {
        return (WebDriver) java.lang.reflect.Proxy.newProxyInstance(
                IsolatedWorldTest.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, HasCapabilities.class, JavascriptExecutor.class, Cdp.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCapabilities" -> {
                        MutableCapabilities caps = new MutableCapabilities();
                        caps.setCapability("browserName", "chrome");
                        yield caps;
                    }
                    case "getWindowHandle" -> "tab";
                    case "getWindowHandles" -> java.util.Set.of("tab");
                    case "executeCdpCommand" -> Map.of("targetInfo", Map.of("type", "page", "targetId", "PAGE"));
                    case "switchTo" -> java.lang.reflect.Proxy.newProxyInstance(
                            IsolatedWorldTest.class.getClassLoader(),
                            new Class<?>[]{WebDriver.TargetLocator.class},
                            (locator, called, locatorArgs) -> null);
                    case "executeScript" -> Map.of("ok", true);
                    case "toString" -> "chrome";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }

    private interface Cdp {
        Object executeCdpCommand(String method, Map<String, Object> params);
    }
}
