package tools.dscode.common.agentcontrol;

import org.openqa.selenium.WebDriver;
import tools.dscode.browser.DebugPortOwner;

/**
 * Registers every driver the existing launcher already started.
 * Not a second launcher. Template launches are marked so session cleanup
 * can reach those instances without quitting a scenario driver early.
 * The host page is marked with {@link #host(boolean)}, not by its template name,
 * and is detached only from host release.
 * Unowned drivers are quit only from worker release.
 */
public final class AgentDriverHooks {
    private static final ThreadLocal<String> TEMPLATE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> HOST = new ThreadLocal<>();

    private AgentDriverHooks() {
    }

    /** Set only around {@code DriverConstruction.createLocalDriver} for {@code launch --start}. */
    public static void template(String name) {
        if (name == null || name.isBlank()) TEMPLATE.remove();
        else TEMPLATE.set(name);
    }

    /** The start host page. A template named {@code live} is not the host. */
    public static void host(boolean host) {
        if (host) HOST.set(Boolean.TRUE);
        else HOST.remove();
    }

    public static void ready(WebDriver driver) {
        String template = TEMPLATE.get();
        boolean owned = template != null && !template.isBlank();
        PageSurfaces.manage(driver, owned ? template : "", owned, Boolean.TRUE.equals(HOST.get()));
        DebugPortOwner.bindPending(driver);
    }

    /**
     * Scenario and surface cleanup. Quits template-owned drivers other than the host page.
     * Does not quit unowned isolate drivers and does not detach the host {@code LiveChromeDriver}.
     */
    public static void quit() {
        RuntimeException cleanupFailure = null;
        UiState.clearAll();
        try {
            PageSurfaces.cleanupExceptHost();
        } catch (RuntimeException failure) {
            cleanupFailure = failure;
        }
        PageSurfaces.quitOwned();
        PageSurfaces.clearExceptHost();
        if (cleanupFailure != null) throw cleanupFailure;
    }

    /** Worker stop and {@code POST /v1/browser/release}. */
    public static void releaseUnowned() {
        PageSurfaces.quitUnowned();
    }

    /** Quits the driver a caller launched. A host driver is ignored. */
    public static void quitSession(WebDriver driver) {
        PageSurfaces.quitOne(driver);
    }

    /** Host process release. The only path that detaches the host page driver. */
    public static void releaseHost() {
        RuntimeException cleanupFailure = null;
        UiState.clearAll();
        try {
            PageSurfaces.cleanupAll();
        } catch (RuntimeException failure) {
            cleanupFailure = failure;
        }
        PageSurfaces.quitUnowned();
        PageSurfaces.quitAll();
        PageSurfaces.quitHost();
        PageSurfaces.clearDriver();
        if (cleanupFailure != null) throw cleanupFailure;
    }
}
