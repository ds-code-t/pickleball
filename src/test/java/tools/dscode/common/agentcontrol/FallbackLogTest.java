package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import tools.dscode.browser.BrowserLog;
import tools.dscode.browser.ProtocolSelector;
import tools.dscode.browser.ProtocolSockets;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackLogTest {
    @AfterEach
    void clean() {
        PageSurfaces.clearDriver();
        ProtocolSelector.resetForTests();
        ProtocolSockets.resetForTests();
        BrowserLog.resetForTests();
    }

    @Test
    void seleniumExecuteScriptIsOneWarn() throws Exception {
        AtomicInteger scripts = new AtomicInteger();
        AtomicInteger sockets = new AtomicInteger();
        ProtocolSockets.useFactoryForTests(uri -> {
            sockets.incrementAndGet();
            return null;
        });
        WebDriver driver = (WebDriver) java.lang.reflect.Proxy.newProxyInstance(
                FallbackLogTest.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWindowHandle" -> "tab";
                    case "getWindowHandles" -> java.util.Set.of("tab");
                    case "executeScript" -> {
                        scripts.incrementAndGet();
                        yield Map.of("ok", true);
                    }
                    case "toString" -> "htmlunit";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
        PageSurfaces.manage(driver, "htmlunit", false);
        PageSurfaces.place(null, "append", null, null, "<b>n</b>", "html", null, null, null);
        PageSurfaces.place(null, "append", null, null, "<b>again</b>", "html", null, null, null);
        assertEquals(2, scripts.get());
        assertEquals(0, sockets.get());
        long warns = BrowserLog.linesForTests().stream()
                .filter(line -> line.contains("reason=page-surface-script"))
                .count();
        assertEquals(1, warns, BrowserLog.linesForTests().toString());
        assertTrue(BrowserLog.linesForTests().get(0).startsWith("WARN browser.fallback"), BrowserLog.linesForTests().toString());
    }
}
