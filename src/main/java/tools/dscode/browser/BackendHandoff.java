package tools.dscode.browser;

import com.microsoft.playwright.Page;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.net.URI;

/**
 * D13 hand-off between Selenium and Playwright on the same live Chromium, without restarting it.
 * Carries over: the browser process, its tabs and pages, cookies, storage, and the default context. Does not carry
 * over: Playwright route handlers, init scripts, exposed bindings, and event listeners (they live in the Playwright
 * client), or Selenium implicit waits, timeouts, and window-handle bookkeeping (they live in the WebDriver session).
 */
public final class BackendHandoff {
    private BackendHandoff() {
    }

    /** The Playwright page for the WebDriver's current URL, over the driver's own CDP endpoint. */
    public static Page toPlaywright(WebDriver driver) {
        String endpoint = PlaywrightConnection.endpointFor(driver);
        Page page = PlaywrightConnection.connect(endpoint).page(driver.getCurrentUrl());
        BrowserLog.info("handoff", "selenium -> playwright " + PlaywrightConnection.redact(endpoint));
        return page;
    }

    /** host:port for Selenium's {@code debuggerAddress} from an http or ws CDP endpoint. */
    public static String debuggerAddress(String endpoint) {
        URI uri = URI.create(endpoint.contains("://") ? endpoint : "http://" + endpoint);
        return uri.getHost() + ":" + uri.getPort();
    }

    /**
     * A ChromeDriver attached with {@code goog:chromeOptions.debuggerAddress} to the browser behind {@code connection},
     * switched to the window showing {@code page}. End it with {@link #release(WebDriver)}.
     */
    public static WebDriver toSelenium(PlaywrightConnection connection, Page page) {
        ChromeOptions options = new ChromeOptions();
        options.setExperimentalOption("debuggerAddress", debuggerAddress(connection.endpoint()));
        ChromeDriver driver = new ChromeDriver(options);
        String url = page == null ? null : page.url();
        if (url != null) {
            for (String handle : driver.getWindowHandles()) {
                driver.switchTo().window(handle);
                if (url.equals(driver.getCurrentUrl())) break;
            }
        }
        BrowserLog.info("handoff", "playwright -> selenium " + PlaywrightConnection.redact(connection.endpoint()));
        return driver;
    }

    /** Ends an attached WebDriver session; a browser joined through debuggerAddress keeps running (tested). */
    public static void release(WebDriver driver) {
        if (driver != null) driver.quit();
    }
}
