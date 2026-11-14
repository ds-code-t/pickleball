package tools.dscode.browser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.composite.CompositeMode;
import tools.dscode.common.composite.CompositeRuntime;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBoundaryTest {
    @AfterEach
    void clean() {
        System.clearProperty(CompositeMode.KEY);
        ProtocolSelector.resetForTests();
        ProtocolSockets.resetForTests();
        BrowserLog.resetForTests();
        CompositeRuntime.resetForTests();
    }

    @Test
    void scriptedCodeDoesNotOpenTheDirectSocket() {
        System.setProperty(CompositeMode.KEY, "auto");
        AtomicInteger sends = new AtomicInteger();
        ScriptedSocket socket = new ScriptedSocket().auto(request -> ScriptedSocket.cdp(request, "{}"));
        ProtocolSockets.useFactoryForTests(uri -> {
            sends.incrementAndGet();
            return socket;
        });
        WebDriver driver = driver("org.openqa.selenium.chrome.ChromeDriver");
        BrowserFacade library = ProtocolSelector.forDriver(driver, ProtocolSelector.Preference.LIBRARY);
        assertEquals("selenium", library.protocol());
        assertEquals(0, sends.get());
        assertThrows(UnsupportedOperationException.class, () -> library.send("Page.getFrameTree", Map.of()));
        assertEquals(0, sends.get());
    }

    @Test
    void theAgentControllerOwnsTheDirectSocket() {
        AtomicInteger sends = new AtomicInteger();
        ScriptedSocket socket = new ScriptedSocket().auto(request -> ScriptedSocket.cdp(request, "{}"));
        ProtocolSockets.useFactoryForTests(uri -> {
            sends.incrementAndGet();
            return socket;
        });
        ProtocolSelector.useEndpointsForTests(driver -> java.net.URI.create("ws://127.0.0.1:9/devtools/browser"));
        WebDriver driver = driver("org.openqa.selenium.chrome.ChromeDriver");
        BrowserFacade direct = AgentProtocolController.open(driver);
        assertEquals("cdp", direct.protocol());
        assertEquals(1, sends.get());
        assertTrue(direct.send("Browser.getVersion", Map.of()) instanceof Map<?, ?>);
        assertEquals(1, socket.sent().size());
    }

    private static WebDriver driver(String className) {
        return (WebDriver) java.lang.reflect.Proxy.newProxyInstance(
                new ClassLoader(AgentBoundaryTest.class.getClassLoader()) {
                    @Override
                    public String getName() {
                        return className;
                    }
                },
                new Class<?>[]{WebDriver.class, org.openqa.selenium.HasCapabilities.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getClass" -> proxy.getClass();
                    case "getCapabilities" -> {
                        org.openqa.selenium.MutableCapabilities caps = new org.openqa.selenium.MutableCapabilities();
                        caps.setCapability("browserName", "chrome");
                        yield caps;
                    }
                    case "toString" -> className;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }
}
