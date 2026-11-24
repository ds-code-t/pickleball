package tools.dscode.browser;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.common.driver.DriverConstruction;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.jserror.JsErrorPageGlue;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.model.StepResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E3 live: pkb_fail_on_js_error over WebDriver BiDi on a real Chrome, through the engine's step lifecycle. */
class JsErrorGateLiveTest {
    @TempDir
    static Path temp;
    static ChromeDriver driver;

    @BeforeAll
    static void start() throws Exception {
        String binary = BrowserSession.findChrome();
        Assumptions.assumeTrue(binary != null, "skipped: Chrome is not present");
        ObjectNode empty = JsonNodeFactory.instance.objectNode();
        ChromeOptions options = DriverConstruction.buildChromeOptions(empty, empty, false);
        options.setBinary(binary);
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu");
        driver = new ChromeDriver(options);
        Path page = temp.resolve("error.html");
        Files.writeString(page, "<!doctype html><title>errors</title><p>page</p>");
        JsErrorPageGlue.driver = driver;
        JsErrorPageGlue.url = page.toUri().toString();
    }

    @AfterAll
    static void stop() {
        if (driver != null) driver.quit();
    }

    @AfterEach
    void clear() {
        System.clearProperty("pkb_fail_on_js_error");
        System.clearProperty("pkb_js_error_ignore");
        JsErrorGate.reset();
    }

    private static RunResult scenario(String message) {
        return PickleballEngine.run("""
                Feature: Page errors
                  Scenario: a page error during a step
                    Given the error page is open
                    When the page throws "%s"
                """.formatted(message), EngineOptions.of(JsErrorPageGlue.class).project(temp));
    }

    private static StepResult step(RunResult result, String text) {
        for (StepResult step : result.scenarios().getFirst().steps()) if (step.text().contains(text)) return step;
        throw new AssertionError("no step " + text + " in " + result);
    }

    @Test
    void failModeFailsTheStepWithTheErrorTextAndStack() {
        System.setProperty("pkb_fail_on_js_error", "fail");
        RunResult result = scenario("kaboom from page");
        assertEquals(Status.FAILED, result.status(), result.toString());
        assertEquals(Status.PASSED, step(result, "error page is open").status());
        StepResult thrown = step(result, "page throws");
        assertEquals(Status.FAILED, thrown.status(), thrown.toString());
        assertTrue(thrown.message().contains("Uncaught page error during step"), thrown.message());
        assertTrue(thrown.message().contains("kaboom from page"), thrown.message());
        assertTrue(thrown.message().contains("pkb_js_error_ignore"), thrown.message());
    }

    @Test
    void ignoreRegexSuppressesAMatchingError() {
        System.setProperty("pkb_fail_on_js_error", "fail");
        System.setProperty("pkb_js_error_ignore", "known\\snoise");
        RunResult result = scenario("known noise");
        assertEquals(Status.PASSED, result.status(), result.message());
    }

    @Test
    void offAndWarnLeaveTheScenarioPassing() {
        assertEquals("off", JsErrorGate.mode());
        assertEquals(Status.PASSED, scenario("quiet by default").status());
        System.setProperty("pkb_fail_on_js_error", "warn");
        assertEquals(Status.PASSED, scenario("only warned").status());
    }
}
