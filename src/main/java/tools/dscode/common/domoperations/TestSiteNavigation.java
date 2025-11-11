package tools.dscode.common.domoperations;

import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chromium.ChromiumDriver;

import java.time.Duration;
import java.util.Scanner;

/**
 * Simple main class for testing LeanWaits and HumanInteractions.
 * - Slowed down with small sleeps
 * - Terminal output for each action
 * - Browser stays open after program ends (via ChromeOptions 'detach')
 */
public class TestSiteNavigation {

    public static void main(String[] args) {
        ChromiumDriver driver = null;
        try {
            System.out.println("[BOOT] Creating ChromeDriver...");
            driver = DriverFactory.createChromeDriver();
            sleep(400);

            System.out.println("[NAV] Going to https://example.com ...");
            driver.get("https://example.com");
            sleep(300);

            System.out.println("[WAIT] Waiting for page to be ready...");
            LeanWaits.waitForPageReady(driver, Duration.ofSeconds(10));
            System.out.println("[OK] Page ready.");
            sleep(300);

            System.out.println("[FIND] Locating the first anchor link on the page...");
            WebElement link = driver.findElement(By.cssSelector("a"));
            System.out.println("       Found link with text: '" + safeText(link) + "'");
            sleep(300);

            System.out.println("[WAIT] Ensuring the link is fully ready for interaction...");
            LeanWaits.waitForElementReady(driver, link, Duration.ofSeconds(5));
            System.out.println("[OK] Link is ready.");
            sleep(300);

            System.out.println("[HOVER] Moving mouse over the link...");
            HumanInteractions.hover(driver, link);
            System.out.println("[OK] Hovered.");
            sleep(500);

            System.out.println("[CLICK] Clicking the link like a human (Actions), with JS fallback if needed...");
            HumanInteractions.click(driver, link);
            System.out.println("[OK] Clicked.");
            sleep(800);

            System.out.println("[DONE] Test steps finished.");
            System.out.println();
            System.out.println(">>> The Chrome window will remain OPEN even after this program exits (detach=true).");
            System.out.println(">>> Press <Enter> to end the program now...");
            new Scanner(System.in).nextLine();

            // End the WebDriver session. With detach=true, the browser window stays open.
            System.out.println("[CLOSE] Ending WebDriver session (browser stays open)...");
            driver.quit();
            System.out.println("[EXIT] Program complete.");

        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) {}
    }

    private static String safeText(WebElement el) {
        try { return el.getText(); } catch (Exception e) { return "(no text)"; }
    }
}
