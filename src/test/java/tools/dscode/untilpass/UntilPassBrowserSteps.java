package tools.dscode.untilpass;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.java.en.Given;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ActionOperations;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opens one headless Chrome for the until-pass browser timing feature.
 * The session stays up across that feature's scenarios. The JUnit test quits it.
 */
public class UntilPassBrowserSteps {
    public static final String CHROME_BINARY = "/opt/pw-browsers/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell";

    private static RemoteWebDriver driver;
    public static long clickSettleMillis = -1;

    @Given("a headless Chrome session is open")
    public void openSession() {
        if (driver == null || driver.getSessionId() == null) {
            ChromeOptions options = new ChromeOptions();
            options.setBinary(CHROME_BINARY);
            options.addArguments(
                    "--headless=new",
                    "--disable-gpu",
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                    "--window-size=1920,1080"
            );
            driver = new ChromeDriver(options);
            driver.manage().timeouts().implicitlyWait(Duration.ofMillis(5000));
        }
        CurrentScenarioState.registerScenarioObject("CHROME", driver);
    }

    @Given("a click still waits to settle")
    public void clickStillSettles() throws Exception {
        Method settle = PhraseData.class.getDeclaredMethod("settleBeforeBrowserAction", tools.dscode.common.treeparsing.parsedComponents.phraseoperations.OperationsInterface.class);
        settle.setAccessible(true);
        long started = System.nanoTime();
        settle.invoke(null, ActionOperations.CLICK);
        clickSettleMillis = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(clickSettleMillis >= 250, "click settle " + clickSettleMillis);
    }

    public static void quit() {
        RemoteWebDriver open = driver;
        driver = null;
        clickSettleMillis = -1;
        if (open != null) {
            try {
                open.quit();
            } catch (RuntimeException ignored) {
                // The timing result is already recorded.
            }
        }
    }
}
