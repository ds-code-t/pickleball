package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
    void quitCallsWebDriverQuitOnAnUnownedDriver() {
        AtomicBoolean quit = new AtomicBoolean(false);
        WebDriver driver = (WebDriver) Proxy.newProxyInstance(
                WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class},
                (proxy, method, args) -> {
                    if ("getWindowHandles".equals(method.getName())) return Set.of();
                    if ("quit".equals(method.getName())) {
                        quit.set(true);
                        return null;
                    }
                    if ("toString".equals(method.getName())) return "unowned-driver";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                }
        );
        AgentDriverHooks.template(null);
        AgentDriverHooks.ready(driver);
        boolean thrown = false;
        try {
            AgentDriverHooks.quit();
        } catch (IllegalStateException failure) {
            thrown = failure.getMessage() != null && failure.getMessage().contains("tab enumeration");
        }
        assertTrue(thrown, "cleanup should report the empty tab set");
        assertTrue(quit.get(), "WebDriver.quit was not called");
    }
}
