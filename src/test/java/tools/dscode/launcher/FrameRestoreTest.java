package tools.dscode.launcher;

import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.framerestore.FramePageGlue;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.model.StepResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phase 7: a step that enters an iframe puts the driver back, and the step reply names that frame. */
class FrameRestoreTest {
    private static final String FEATURE = """
            Feature: Frame restore
              Scenario: the next step is on the page
                Given the frame page is open
                When , in the "Box" IFrame, click the "Inner" Button
                Then , click the "Outer" Button
              Scenario: a miss still restores
                Given the frame page is open
                When , in the "Box" IFrame, click the "Missing" Button
                Then ALWAYS RUN: , click the "Outer" Button
            """;

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
        ChromeDriver engine = FramePageGlue.driver;
        FramePageGlue.driver = null;
        if (engine != null) {
            try {
                engine.quit();
            } catch (RuntimeException ignored) {
                // The assertion already ran.
            }
        }
        System.clearProperty("pkb_composite");
        CompositeRuntime.resetForTests();
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void inFrameClickRestoresThePageForTheNextStep() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("frames.html");
        Files.writeString(page, """
                <!DOCTYPE html>
                <html><head><title>Ready</title></head>
                <body>
                <button id="outer">Outer</button>
                <iframe id="Box" name="Box" title="Box" width="480" height="240"
                  srcdoc="<html><body><button id='inner'>Inner</button><script>document.getElementById('inner').onclick=function(){parent.innerClicks++;parent.document.title='Inner';}</script></body></html>"></iframe>
                <script>
                window.innerClicks = 0;
                window.outerClicks = 0;
                document.getElementById('outer').onclick = function () {
                  document.title = 'Outer';
                  window.outerClicks++;
                };
                </script>
                </body></html>
                """);
        ChromeOptions options = new ChromeOptions();
        options.setBinary(BrowserSession.findChrome());
        options.addArguments("--headless=new", "--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage");
        FramePageGlue.driver = new ChromeDriver(options);
        FramePageGlue.url = page.toUri().toString();
        CompositeRuntime.resetForTests();
        System.setProperty("pkb_composite", "off");

        RunResult passed = PickleballEngine.run(
                FEATURE, EngineOptions.of(FramePageGlue.class).project(temp).scenario("the next step is on the page"));
        assertEquals(Status.PASSED, passed.status(), passed.message());
        StepResult inner = step(passed, "in the \"Box\" IFrame, click the \"Inner\" Button");
        assertEquals(Status.PASSED, inner.status(), inner.toString());
        assertEquals("Box", inner.frame(), inner.toString());
        StepResult outer = step(passed, "click the \"Outer\" Button");
        assertEquals(Status.PASSED, outer.status(), outer.toString());
        assertEquals("", outer.frame(), outer.toString());
        assertFalse(outer.text().contains("IFrame"), outer.text());
        assertEquals(1L, count("innerClicks"), "inner");
        assertEquals(1L, count("outerClicks"), "outer");
        assertEquals("Outer", title());
        assertEquals(Boolean.TRUE, topDocument());

        RunResult failed = PickleballEngine.run(
                FEATURE, EngineOptions.of(FramePageGlue.class).project(temp).scenario("a miss still restores"));
        StepResult missing = step(failed, "in the \"Box\" IFrame, click the \"Missing\" Button");
        assertEquals(Status.FAILED, missing.status(), missing.toString());
        assertEquals("Box", missing.frame(), missing.toString());
        assertOuterRestored(failed);
        assertEquals(0L, count("innerClicks"), "inner after the miss");
        assertEquals(1L, count("outerClicks"), "outer after the miss");
        assertEquals("Outer", title());
        assertEquals(Boolean.TRUE, topDocument());
    }

    private static StepResult step(RunResult result, String text) {
        List<StepResult> found = matching(result, text);
        assertFalse(found.isEmpty(), result.toString());
        return found.getFirst();
    }

    private static void assertOuterRestored(RunResult result) {
        List<StepResult> found = new ArrayList<>();
        for (StepResult step : matching(result, "click the \"Outer\" Button")) {
            if (step.text().contains("IFrame")) continue;
            found.add(step);
            assertEquals(Status.PASSED, step.status(), step.toString());
            assertEquals("", step.frame(), step.toString());
        }
        assertFalse(found.isEmpty(), result.toString());
    }

    private static List<StepResult> matching(RunResult result, String text) {
        List<StepResult> found = new ArrayList<>();
        for (StepResult step : result.scenarios().getFirst().steps()) {
            if (step.text() != null && step.text().contains(text)) found.add(step);
        }
        return found;
    }

    private static long count(String name) {
        Object value = FramePageGlue.driver.executeScript("return window." + name);
        assertTrue(value instanceof Number, String.valueOf(value));
        return ((Number) value).longValue();
    }

    private static String title() {
        return String.valueOf(FramePageGlue.driver.executeScript("return document.title"));
    }

    private static Object topDocument() {
        return FramePageGlue.driver.executeScript(
                "return window.frameElement == null && document.getElementById('outer') != null");
    }
}
