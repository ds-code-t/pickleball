package tools.dscode.common.agentcontrol;

import org.openqa.selenium.WebDriver;

/**
 * Registers every driver the existing launcher already started.
 * Not a second launcher. Template launches are marked so session cleanup
 * can reach those instances without quitting a scenario driver early.
 */
public final class AgentDriverHooks {
    private static final ThreadLocal<String> TEMPLATE = new ThreadLocal<>();

    private AgentDriverHooks() {
    }

    /** Set only around {@code DriverConstruction.createLocalDriver} for {@code launch --start}. */
    public static void template(String name) {
        if (name == null || name.isBlank()) TEMPLATE.remove();
        else TEMPLATE.set(name);
    }

    public static void ready(WebDriver driver) {
        String template = TEMPLATE.get();
        boolean owned = template != null && !template.isBlank();
        PageSurfaces.manage(driver, owned ? template : "", owned);
    }

    public static void quit() {
        try {
            PageSurfaces.cleanupAll();
        } catch (RuntimeException ignored) {
            // Scenario end must still release agent-owned drivers.
        }
        PageSurfaces.quitOwned();
        PageSurfaces.clearDriver();
    }
}
