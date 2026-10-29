package tools.dscode.untilpass;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.java.en.Given;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ActionOperations;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opens one headless Chrome for the until-pass browser timing feature.
 * The session stays up across that feature's scenarios. The JUnit test quits it.
 * The binary comes from an env var, a system property, or a filesystem search.
 * When none of those exist, Selenium Manager chooses the binary.
 */
public class UntilPassBrowserSteps {
    private static RemoteWebDriver driver;
    public static long clickSettleMillis = -1;

    @Given("a headless Chrome session is open")
    public void openSession() {
        if (driver == null || driver.getSessionId() == null) {
            ChromeOptions options = new ChromeOptions();
            String binary = chromeBinary();
            if (binary != null) options.setBinary(binary);
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

    /**
     * First existing executable wins. A missing candidate is skipped.
     * {@code null} means do not call {@code setBinary}.
     */
    static String chromeBinary() {
        List<String> candidates = new ArrayList<>();
        add(candidates, System.getenv("PKB_CHROME_BINARY"));
        add(candidates, System.getenv("CHROME_BINARY"));
        add(candidates, System.getProperty("pickleball.chrome.binary"));
        add(candidates, System.getProperty("webdriver.chrome.binary"));
        add(candidates, "/opt/pw-browsers/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell");
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                add(candidates, Path.of(dir, "chrome").toString());
                add(candidates, Path.of(dir, "google-chrome").toString());
                add(candidates, Path.of(dir, "chromium").toString());
                add(candidates, Path.of(dir, "chrome-headless-shell").toString());
            }
        }
        Path bundled = Path.of("/opt/pw-browsers");
        if (Files.isDirectory(bundled)) {
            try (Stream<Path> walk = Files.walk(bundled, 6)) {
                walk.filter(file -> Files.isRegularFile(file) && executableName(file)).forEach(file -> add(candidates, file.toString()));
            } catch (IOException ignored) {
                // Discovery is best-effort. Selenium Manager is the fallback.
            }
        }
        for (String candidate : candidates) {
            Path file = Path.of(candidate);
            if (Files.isRegularFile(file) && Files.isExecutable(file)) return file.toString();
        }
        return null;
    }

    private static boolean executableName(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        return "chrome".equals(name) || "google-chrome".equals(name) || "chromium".equals(name)
                || "chrome-headless-shell".equals(name);
    }

    private static void add(List<String> candidates, String value) {
        if (value != null && !value.isBlank()) candidates.add(value.trim());
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
