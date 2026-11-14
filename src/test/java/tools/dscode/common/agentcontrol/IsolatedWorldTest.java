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

import java.util.List;
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

    @Test
    void cleanupEnablesOverlayThenHidesAndSkipsAFrameThatIsGone() throws Exception {
        ScriptedSocket socket = cleanupSocket("{\"frameTree\":{\"frame\":{\"id\":\"ROOT\"}}}");
        ProtocolSockets.useFactoryForTests(uri -> socket);
        ProtocolSelector.useEndpointsForTests(driver -> java.net.URI.create("ws://127.0.0.1:9/devtools/browser"));
        WebDriver driver = pageDriver();
        PageSurfaces.manage(driver, "chrome-headed-quiet", true);
        PageSurfaces.bindFrame(driver, "tab", "main");
        Map<String, Object> cleaned = PageSurfaces.cleanup(null);
        assertEquals(true, cleaned.get("cleaned"));
        List<String> methods = socket.methods();
        int hide = methods.indexOf("Overlay.hideHighlight");
        assertTrue(hide > 0, methods.toString());
        assertEquals("Overlay.enable", methods.get(hide - 1), methods.toString());
        assertFalse(methods.contains("DOM.getFrameOwner"), methods.toString());
    }

    @Test
    void childFrameEntryEnablesDomBeforeTheOwner() throws Exception {
        ScriptedSocket socket = cleanupSocket("""
                {"frameTree":{"frame":{"id":"ROOT"},"childFrames":[{"frame":{"id":"CHILD"}}]}}""");
        ProtocolSockets.useFactoryForTests(uri -> socket);
        ProtocolSelector.useEndpointsForTests(driver -> java.net.URI.create("ws://127.0.0.1:9/devtools/browser"));
        WebDriver driver = pageDriver();
        PageSurfaces.manage(driver, "chrome-headed-quiet", true);
        Map<String, Object> cleaned = PageSurfaces.cleanup(null);
        assertEquals(true, cleaned.get("cleaned"));
        List<String> methods = socket.methods();
        int owner = methods.indexOf("DOM.getFrameOwner");
        assertTrue(owner > 0, methods.toString());
        assertEquals("DOM.enable", methods.get(owner - 2), methods.toString());
        assertEquals("Runtime.enable", methods.get(owner - 1), methods.toString());
    }

    @Test
    void bidiCleanupSkipsOverlayHide() throws Exception {
        ScriptedSocket socket = new ScriptedSocket().auto(request -> {
            String method = ScriptedSocket.method(request);
            return switch (method) {
                case "Page.getFrameTree" -> ScriptedSocket.cdp(request, "{\"frameTree\":{\"frame\":{\"id\":\"ROOT\"}}}");
                case "browsingContext.getTree" -> ScriptedSocket.cdp(request,
                        "{\"contexts\":[{\"context\":\"ctx\",\"url\":\"https://example.test\",\"children\":[]}]}");
                case "script.callFunction" -> ScriptedSocket.cdp(request,
                        "{\"type\":\"object\",\"value\":{\"ok\":true}}");
                default -> ScriptedSocket.cdp(request, "{}");
            };
        });
        ProtocolSockets.useFactoryForTests(uri -> socket);
        WebDriver driver = firefoxDriver();
        PageSurfaces.manage(driver, "firefox", true);
        Map<String, Object> cleaned = PageSurfaces.cleanup(null);
        assertEquals(true, cleaned.get("cleaned"));
        assertFalse(socket.methods().contains("Overlay.hideHighlight"), socket.methods().toString());
        assertFalse(socket.methods().contains("Overlay.enable"), socket.methods().toString());
        assertTrue(socket.methods().contains("script.callFunction"), socket.methods().toString());
    }

    private static ScriptedSocket cleanupSocket(String frameTree) {
        return new ScriptedSocket().auto(request -> {
            String method = ScriptedSocket.method(request);
            return switch (method) {
                case "Target.attachToTarget" -> ScriptedSocket.cdp(request, "{\"sessionId\":\"sess-9\"}");
                case "Page.getFrameTree" -> ScriptedSocket.cdp(request, frameTree);
                case "Page.createIsolatedWorld" -> ScriptedSocket.cdp(request, "{\"executionContextId\":7}");
                case "Runtime.evaluate" -> ScriptedSocket.cdp(request,
                        "{\"result\":{\"type\":\"object\",\"value\":{\"ok\":true}}}");
                case "DOM.getFrameOwner" -> ScriptedSocket.cdp(request, "{\"backendNodeId\":7}");
                case "DOM.resolveNode" -> ScriptedSocket.cdp(request, "{\"object\":{\"objectId\":\"obj-frame\"}}");
                case "Runtime.callFunctionOn" -> request.contains("removeAttribute")
                        ? ScriptedSocket.cdp(request, "{\"result\":{\"value\":\"true\"}}")
                        : ScriptedSocket.cdp(request,
                        "{\"result\":{\"value\":\"{\\\"name\\\":\\\"child\\\",\\\"changedName\\\":false,\\\"hadName\\\":true,\\\"previousName\\\":\\\"child\\\",\\\"hadBridge\\\":false,\\\"previousBridge\\\":null}\"}}");
                default -> ScriptedSocket.cdp(request, "{}");
            };
        });
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

    private static WebDriver firefoxDriver() {
        return (WebDriver) java.lang.reflect.Proxy.newProxyInstance(
                IsolatedWorldTest.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, HasCapabilities.class, JavascriptExecutor.class, Cdp.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCapabilities" -> {
                        MutableCapabilities caps = new MutableCapabilities();
                        caps.setCapability("browserName", "firefox");
                        caps.setCapability("webSocketUrl", "ws://127.0.0.1:9/session");
                        yield caps;
                    }
                    case "getWindowHandle" -> "tab";
                    case "getWindowHandles" -> java.util.Set.of("tab");
                    case "executeCdpCommand" -> Map.of();
                    case "switchTo" -> java.lang.reflect.Proxy.newProxyInstance(
                            IsolatedWorldTest.class.getClassLoader(),
                            new Class<?>[]{WebDriver.TargetLocator.class},
                            (locator, called, locatorArgs) -> null);
                    case "executeScript" -> Map.of("ok", true);
                    case "toString" -> "firefox";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }

    private interface Cdp {
        Object executeCdpCommand(String method, Map<String, Object> params);
    }
}
