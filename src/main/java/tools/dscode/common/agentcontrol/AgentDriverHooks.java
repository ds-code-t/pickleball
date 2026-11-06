package tools.dscode.common.agentcontrol;

import org.openqa.selenium.WebDriver;

/** Holds the live driver the existing launcher already started. Not a second launcher. */
public final class AgentDriverHooks {
    private AgentDriverHooks() {
    }

    public static void ready(WebDriver driver) {
        PageSurfaces.hold(driver);
    }

    public static void quit() {
        try {
            PageSurfaces.cleanupAll();
        } catch (RuntimeException ignored) {
            // Scenario end must still quit the driver.
        }
        PageSurfaces.clearDriver();
    }
}
