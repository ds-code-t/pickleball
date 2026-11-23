package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Alert;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.WebDriver;
import tools.dscode.browser.BrowserLog;
import tools.dscode.browser.ProtocolSelector;
import tools.dscode.browser.ProtocolSockets;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A dialog blocks the page socket. {@code runtimeCall} is the production installer
 * of {@code Runtime.addBinding}. Opening that socket or running a page script fails this test.
 */
class DialogBlocksBindingTest {
    @AfterEach
    void clean() {
        PageSurfaces.clearDriver();
        ProtocolSelector.resetForTests();
        ProtocolSockets.resetForTests();
        BrowserLog.resetForTests();
    }

    @Test
    void anOpenDialogDoesNotSendRuntimeAddBinding() {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger scripts = new AtomicInteger();
        ProtocolSockets.useFactoryForTests(uri -> {
            opened.incrementAndGet();
            throw new IllegalStateException("socket opened while a dialog is up");
        });
        ProtocolSelector.useEndpointsForTests(driver -> java.net.URI.create("ws://127.0.0.1:9/devtools/browser"));
        WebDriver driver = chromeWithDialog(scripts);
        PageSurfaces.manage(driver, "chrome-headed-quiet", true);
        assertNull(PageSurfaces.runtimeCall("panelOpen", Map.of(), true));
        assertEquals(0, opened.get());
        assertEquals(0, scripts.get());
    }

    private static WebDriver chromeWithDialog(AtomicInteger scripts) {
        Alert alert = (Alert) java.lang.reflect.Proxy.newProxyInstance(
                DialogBlocksBindingTest.class.getClassLoader(),
                new Class<?>[]{Alert.class},
                (proxy, method, args) -> null);
        WebDriver.TargetLocator locator = (WebDriver.TargetLocator) java.lang.reflect.Proxy.newProxyInstance(
                DialogBlocksBindingTest.class.getClassLoader(),
                new Class<?>[]{WebDriver.TargetLocator.class},
                (proxy, method, args) -> "alert".equals(method.getName()) ? alert : null);
        return (WebDriver) java.lang.reflect.Proxy.newProxyInstance(
                DialogBlocksBindingTest.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, HasCapabilities.class, JavascriptExecutor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCapabilities" -> {
                        MutableCapabilities caps = new MutableCapabilities();
                        caps.setCapability("browserName", "chrome");
                        yield caps;
                    }
                    case "switchTo" -> locator;
                    case "executeScript" -> {
                        scripts.incrementAndGet();
                        yield null;
                    }
                    case "getWindowHandle" -> "tab";
                    case "getWindowHandles" -> java.util.Set.of("tab");
                    case "toString" -> "chrome";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }
}
