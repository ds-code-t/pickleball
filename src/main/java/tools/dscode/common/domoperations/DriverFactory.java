package tools.dscode.common.domoperations;

import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

/** Simple utility for creating a configured ChromeDriver. */
public final class DriverFactory {

    private DriverFactory() {}

    /** Builds a ChromeDriver with sane defaults and keeps the browser open after quit(). */
    public static ChromeDriver createChromeDriver() {
        ChromeOptions options = new ChromeOptions();

        // Common safe defaults
        options.addArguments("--start-maximized");
        options.addArguments("--disable-notifications");
        options.addArguments("--disable-infobars");
        options.addArguments("--disable-extensions");
        options.addArguments("--remote-allow-origins=*");

        // Keep the browser window open after the session ends:
        options.setExperimentalOption("detach", true);

        // Uncomment to run headless (keeps detach behavior irrelevant in headless):
        // options.addArguments("--headless=new", "--window-size=1920,1080");

        return new ChromeDriver(options);
    }
}
