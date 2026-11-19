package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B4: quit reaches an isolate or scenario driver that launch --start did not own. */
class UnownedDriverQuitTest {
    @AfterEach
    void clear() {
        AgentDriverHooks.template(null);
        try {
            PageSurfaces.quitAll();
        } catch (RuntimeException ignored) {
            // The assertion already ran.
        }
        PageSurfaces.clearDriver();
    }

    @Test
    void releaseUnownedCallsWebDriverQuit() {
        AtomicBoolean quit = new AtomicBoolean(false);
        WebDriver driver = proxy(quit, "unowned-driver");
        AgentDriverHooks.template(null);
        AgentDriverHooks.ready(driver);
        AgentDriverHooks.releaseUnowned();
        assertTrue(quit.get(), "WebDriver.quit was not called");
    }

    @Test
    void quitLeavesTheHostAndReleaseHostQuitsIt() {
        AtomicBoolean quit = new AtomicBoolean(false);
        WebDriver driver = proxy(quit, "live-host");
        AgentDriverHooks.template("live");
        AgentDriverHooks.ready(driver);
        AgentDriverHooks.template(null);
        AgentDriverHooks.quit();
        assertFalse(quit.get(), "shared quit detached the host page");
        try {
            AgentDriverHooks.releaseHost();
        } catch (IllegalStateException ignored) {
            // Cleanup on an empty tab set still reaches quitHost.
        }
        assertTrue(quit.get(), "releaseHost did not quit the host page");
    }

    private static WebDriver proxy(AtomicBoolean quit, String name) {
        return (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class},
                (proxy, method, args) -> {
                    if ("getWindowHandles".equals(method.getName())) return Set.of();
                    if ("quit".equals(method.getName())) {
                        quit.set(true);
                        return null;
                    }
                    if ("toString".equals(method.getName())) return name;
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                }
        );
    }
}
