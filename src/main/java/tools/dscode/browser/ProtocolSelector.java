package tools.dscode.browser;

import org.openqa.selenium.WebDriver;

/**
 * Picks the adapter for a driver.
 *
 * <p>Phase 1 selects {@link SeleniumAdapter} for every driver, including
 * {@code ChromeDriver}. That is the scripted library path. No caller of
 * {@code ElementOperations} changes. Direct CDP and BiDi selection arrives
 * with the socket adapters; until then this method does not open a socket.
 */
public final class ProtocolSelector {
    private ProtocolSelector() {
    }

    public static BrowserFacade forDriver(WebDriver driver) {
        if (driver == null) {
            throw new IllegalArgumentException("error: driver is null.\nfix: Pass the live WebDriver.");
        }
        return new SeleniumAdapter(driver);
    }
}
