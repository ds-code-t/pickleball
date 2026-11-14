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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BindingEventTest {
    @AfterEach
    void clean() {
        PageSurfaces.clearDriver();
        ProtocolSelector.resetForTests();
        ProtocolSockets.resetForTests();
        BrowserLog.resetForTests();
    }

    @Test
    void bindingCalledIsPushedWithoutAPoll() throws Exception {
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
        PageSurfaces.manage(driver(), "chrome-headed-quiet", true);
        PageSurfaces.place(null, "append", null, null, "<b>n</b>", "html", null, null, null);
        assertTrueBound();
        socket.inbound("""
                {"method":"Runtime.bindingCalled","params":{"name":"pkbSurface","payload":{"type":"click","id":"s1"}}}""");
        Map<String, String> event = PageSurfaces.pushedForTests().get(0);
        assertEquals("click", event.get("type"));
        assertEquals("s1", event.get("id"));
        assertEquals("pkbSurface", event.get("binding"));
    }

    private static void assertTrueBound() {
        assertFalse(PageSurfaces.pushedForTests().iterator().hasNext());
    }

    private static WebDriver driver() {
        return (WebDriver) java.lang.reflect.Proxy.newProxyInstance(
                BindingEventTest.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, HasCapabilities.class, JavascriptExecutor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCapabilities" -> {
                        MutableCapabilities caps = new MutableCapabilities();
                        caps.setCapability("browserName", "chrome");
                        yield caps;
                    }
                    case "getWindowHandle" -> "tab";
                    case "getWindowHandles" -> java.util.Set.of("tab");
                    case "executeScript" -> Map.of("ok", true);
                    case "toString" -> "chrome";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }
}
