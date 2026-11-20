package tools.dscode.launcher;

import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.coredefinitions.NavigationSteps;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.model.StepResult;
import tools.dscode.engine.navfile.NavFileGlue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phase 7: file, data, and about:blank resolve and load in Chrome through the navigate step. */
class NavigateDataFileTest {
    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
        ChromeDriver engine = NavFileGlue.driver;
        NavFileGlue.driver = null;
        if (engine != null) {
            try {
                engine.quit();
            } catch (RuntimeException ignored) {
                // The assertion already ran.
            }
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void fileDataAndBlankNavigate() throws Exception {
        assertEquals("file:/tmp/page.html", NavigationSteps.addressAfterConfigLookup("file:/tmp/page.html", null));
        assertEquals("data:text/html,Hi", NavigationSteps.addressAfterConfigLookup("data:text/html,Hi", null));
        assertEquals("about:blank", NavigationSteps.addressAfterConfigLookup("about:blank", null));
        assertEquals("about:blank?x=1", NavigationSteps.addressAfterConfigLookup("about:blank?x=1", null));
        assertNull(NavigationSteps.addressAfterConfigLookup("data:", null));
        assertNull(NavigationSteps.addressAfterConfigLookup("file:", null));
        assertNull(NavigationSteps.addressAfterConfigLookup("not a url", null));
        assertNull(NavigationSteps.addressAfterConfigLookup("URL.missing", null));
        assertEquals(
                "https://from-config.example/home",
                NavigationSteps.addressAfterConfigLookup("file:/tmp/page.html", "https://from-config.example/home")
        );

        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("file.html");
        Files.writeString(page, "<!DOCTYPE html><html><head><title>FilePage</title></head><body>File</body></html>");
        String fileUrl = page.toUri().toString();
        assertTrue(fileUrl.startsWith("file:"), fileUrl);
        assertEquals(fileUrl, NavigationSteps.addressAfterConfigLookup(fileUrl, null));

        ChromeOptions options = new ChromeOptions();
        options.setBinary(BrowserSession.findChrome());
        options.addArguments("--headless=new", "--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage");
        NavFileGlue.driver = new ChromeDriver(options);

        assertNotEquals("FilePage", read("document.title"), "the file page was already open");
        RunResult file = navigate(fileUrl, "the file page loads");
        assertEquals(Status.PASSED, file.status(), file.toString());
        StepResult fileStep = navigateStep(file);
        assertEquals("navigate to: " + fileUrl, fileStep.text(), fileStep.toString());
        assertEquals(Status.PASSED, fileStep.status(), fileStep.toString());
        assertEquals("FilePage", read("document.title"));

        String dataUrl = "data:text/html,<title>DataPage</title>";
        assertEquals(dataUrl, NavigationSteps.addressAfterConfigLookup(dataUrl, null));
        assertNotEquals("DataPage", read("document.title"), "the data page was already open");
        RunResult data = navigate(dataUrl, "the data page loads");
        assertEquals(Status.PASSED, data.status(), data.toString());
        StepResult dataStep = navigateStep(data);
        assertEquals("navigate to: " + dataUrl, dataStep.text(), dataStep.toString());
        assertEquals(Status.PASSED, dataStep.status(), dataStep.toString());
        assertEquals("DataPage", read("document.title"));
        assertTrue(read("location.href").startsWith("data:"), read("location.href"));

        assertNotEquals("about:blank", read("location.href"), "the blank page was already open");
        RunResult blank = navigate("about:blank", "the blank page loads");
        assertEquals(Status.PASSED, blank.status(), blank.toString());
        StepResult blankStep = navigateStep(blank);
        assertEquals("navigate to: about:blank", blankStep.text(), blankStep.toString());
        assertEquals(Status.PASSED, blankStep.status(), blankStep.toString());
        assertEquals("about:blank", read("location.href"));
    }

    private RunResult navigate(String url, String scenario) {
        String feature = "Feature: Navigate literals\n"
                + "  Scenario: " + scenario + "\n"
                + "    Given the chrome page is ready\n"
                + "    When navigate to: " + url + "\n";
        return PickleballEngine.run(
                feature,
                EngineOptions.of(NavFileGlue.class).project(temp).scenario(scenario));
    }

    private static StepResult navigateStep(RunResult result) {
        assertFalse(result.scenarios().isEmpty(), result.toString());
        List<StepResult> found = new ArrayList<>();
        for (StepResult step : result.scenarios().getFirst().steps()) {
            if (step.text() != null && step.text().startsWith("navigate to:")) found.add(step);
        }
        assertEquals(1, found.size(), result.toString());
        return found.getFirst();
    }

    private static String read(String expression) {
        Object value = NavFileGlue.driver.executeScript("return " + expression);
        return value == null ? "" : String.valueOf(value);
    }
}
