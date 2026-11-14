package tools.dscode.browser;

import org.openqa.selenium.WebDriver;
import tools.dscode.common.composite.CompositeRuntime;

import java.util.Map;

/**
 * Direct CDP/BiDi for an agent or MCP caller.
 * Scripted {@code ElementOperations} does not call this class.
 * {@link CompositeRuntime#focusPage} pins the engine session when one is open.
 * It is not reimplemented here.
 */
public final class AgentProtocolController {
    private AgentProtocolController() {
    }

    public static BrowserFacade open(WebDriver driver) {
        CompositeRuntime.focusPage(driver, null);
        BrowserFacade facade = ProtocolSelector.forDriver(driver, ProtocolSelector.Preference.DIRECT);
        if ("selenium".equals(facade.protocol())) {
            BrowserLog.warnOnce("agent-library",
                    "agent direct open fell back to SeleniumAdapter; no CDP or BiDi endpoint");
        }
        BrowserLog.info("facade", "agent " + facade.protocol());
        return facade;
    }

    public static Object send(WebDriver driver, String method, Map<String, ?> params) {
        return open(driver).send(method, params);
    }

    public static Object evaluateIsolated(WebDriver driver, String worldName, String fn, Object... args) {
        return open(driver).evaluateIsolated(worldName, fn, args);
    }

    public static void addPreload(WebDriver driver, String script) {
        open(driver).addPreload(script);
    }

    public static void addBinding(WebDriver driver, String name, BindingListener listener) {
        open(driver).addBinding(name, listener);
    }
}
