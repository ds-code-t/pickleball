package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.composite.ElementOperations;
import tools.dscode.common.domoperations.HumanInteractions;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.maskquiet.MaskPageGlue;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.model.StepResult;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phase 7: a click waits only for a mask that covers the target, then the title changes. */
class MaskQuietTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
        ChromeDriver engine = MaskPageGlue.driver;
        MaskPageGlue.driver = null;
        if (engine != null) {
            try {
                engine.quit();
            } catch (RuntimeException ignored) {
                // The assertion already ran.
            }
        }
        CompositeRuntime.resetForTests();
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void clickWaitsUntilTheMaskLeaves() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("mask.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <div id="mask" role="progressbar" aria-busy="true" style="position:fixed;inset:0">Working</div>
                <button id="open">Inbox</button>
                <script>
                document.getElementById('open').onclick = function () {
                  if (document.getElementById('mask')) return;
                  document.title = 'Inbox';
                };
                </script>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=mask");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);
        assertEquals("Ready", eval("document.title", "mask"));
        for (int pass = 1; pass <= 10; pass++) {
            assertEquals("armed", eval("""
                    (function(){
                      document.title = 'Ready';
                      var old = document.getElementById('mask');
                      if (old) old.remove();
                      var mask = document.createElement('div');
                      mask.id = 'mask';
                      mask.setAttribute('role', 'progressbar');
                      mask.setAttribute('aria-busy', 'true');
                      mask.style.cssText = 'position:fixed;inset:0;background:#fff';
                      mask.textContent = 'Working';
                      document.body.appendChild(mask);
                      setTimeout(function(){ var live = document.getElementById('mask'); if (live) live.remove(); }, 900);
                      return 'armed';
                    })()
                    """, "mask"), "pass " + pass);

            Capture acted = run(
                    "act", "--target=browser:auto", "--query=Inbox", "--kind=click", "--timeout=5000", "--run-id=mask");
            assertEquals(0, acted.code, acted.err + acted.out);
            JsonNode body = JSON.readTree(acted.out);
            assertTrue(body.path("applied").asBoolean(), pass + " " + acted.out);
            assertTrue(body.path("waitedMs").asLong() >= 800, pass + " " + acted.out);
            assertTrue(body.path("maskCleared").asBoolean(), pass + " " + acted.out);
            assertEquals("Inbox", eval("document.title", "mask"), pass + " " + acted.out);
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void unrelatedProgressBarDoesNotDelayTheClick() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("upload.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <div id="up" role="progressbar" aria-busy="true" style="position:fixed;left:0;top:0;width:48px;height:10px">Up</div>
                <button id="go" style="position:fixed;left:40px;top:160px">Go</button>
                <script>document.getElementById('go').onclick = function () { document.title = 'Gone'; };</script>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=upload");
        assertEquals(0, started.code, started.err + started.out);
        Capture acted = run(
                "act", "--target=browser:auto", "--query=Go", "--kind=click", "--timeout=5000", "--run-id=upload");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode body = JSON.readTree(acted.out);
        assertTrue(body.path("applied").asBoolean(), acted.out);
        assertEquals(0, body.path("waitedMs").asLong(), acted.out);
        assertFalse(body.path("maskCleared").asBoolean(), acted.out);
        assertTrue(body.path("elapsedMs").asLong() < 1000, acted.out);
        assertEquals("Gone", eval("document.title", "upload"));
        assertEquals("progressbar", eval("document.getElementById('up').getAttribute('role')", "upload"));
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void gherkinClickWaitsForTheCoveringMask() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("engine-mask.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <div id="mask" role="progressbar" aria-busy="true" style="position:fixed;inset:0;background:#fff">Working</div>
                <button id="open">Inbox</button>
                <script>
                document.getElementById('open').onclick = function () {
                  window.clickedAt = Date.now();
                  if (document.getElementById('mask')) { window.early = true; return; }
                  document.title = 'Inbox';
                };
                </script>
                </body></html>
                """);
        ChromeOptions options = new ChromeOptions();
        options.setBinary(BrowserSession.findChrome());
        options.addArguments("--headless=new", "--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage");
        MaskPageGlue.driver = new ChromeDriver(options);
        MaskPageGlue.url = page.toUri().toString();
        CompositeRuntime.resetForTests();
        System.setProperty("pkb_composite", "off");
        try {
            RunResult result = PickleballEngine.run("""
                    Feature: Covering mask
                      Scenario: the click waits
                        Given a covering mask is armed
                        When , click the "Inbox" Button
                    """, EngineOptions.of(MaskPageGlue.class).project(temp));
            assertEquals(Status.PASSED, result.status(), result.message());
            StepResult click = null;
            for (StepResult step : result.scenarios().getFirst().steps()) {
                if (step.text().contains("click the")) click = step;
            }
            assertTrue(click != null, result.toString());
            assertEquals(Status.PASSED, click.status(), click.message());
            assertTrue(click.durationMillis() >= 800, click.toString());
            Object read = MaskPageGlue.driver.executeScript(
                    "return document.title + '|' + window.early + '|' + (window.clickedAt - window.armedAt)");
            String[] parts = String.valueOf(read).split("\\|");
            assertEquals("Inbox", parts[0], String.valueOf(read));
            assertEquals("false", parts[1], String.valueOf(read));
            assertTrue(Long.parseLong(parts[2]) >= 800, String.valueOf(read));

            Object armed = MaskPageGlue.driver.executeScript("""
                    document.title = 'Ready';
                    window.early = false;
                    window.clickedAt = 0;
                    var old = document.getElementById('mask');
                    if (!old) {
                      old = document.createElement('div');
                      old.id = 'mask';
                      old.setAttribute('role', 'progressbar');
                      old.setAttribute('aria-busy', 'true');
                      old.style.cssText = 'position:fixed;inset:0;background:#fff';
                      document.body.appendChild(old);
                    }
                    window.armedAt = Date.now();
                    setTimeout(function(){ var live = document.getElementById('mask'); if (live) live.remove(); }, 900);
                    return 'armed';
                    """);
            assertEquals("armed", String.valueOf(armed));
            long started = System.nanoTime();
            ElementOperations.click(MaskPageGlue.driver, MaskPageGlue.driver.findElement(By.id("open")));
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(elapsed >= 800, "element click " + elapsed);
            assertEquals("Inbox", MaskPageGlue.driver.getTitle());
            assertEquals("false", String.valueOf(MaskPageGlue.driver.executeScript("return String(window.early)")));

            CompositeRuntime.resetForTests();
            System.setProperty("pkb_composite", "off");
            Object rearmed = MaskPageGlue.driver.executeScript("""
                    document.title = 'Ready';
                    window.early = false;
                    var mask = document.createElement('div');
                    mask.id = 'mask';
                    mask.setAttribute('role', 'progressbar');
                    mask.setAttribute('aria-busy', 'true');
                    mask.style.cssText = 'position:fixed;inset:0;background:#fff';
                    document.body.appendChild(mask);
                    window.armedAt = Date.now();
                    setTimeout(function(){ var live = document.getElementById('mask'); if (live) live.remove(); }, 900);
                    return 'armed';
                    """);
            assertEquals("armed", String.valueOf(rearmed));
            long humanStarted = System.nanoTime();
            HumanInteractions.click(MaskPageGlue.driver, MaskPageGlue.driver.findElement(By.id("open")));
            long humanElapsed = (System.nanoTime() - humanStarted) / 1_000_000L;
            assertTrue(humanElapsed >= 800, "human click " + humanElapsed);
            assertEquals("Inbox", MaskPageGlue.driver.getTitle());
            assertEquals("false", String.valueOf(MaskPageGlue.driver.executeScript("return String(window.early)")));
        } finally {
            System.clearProperty("pkb_composite");
            CompositeRuntime.resetForTests();
        }
    }

    private String eval(String expr, String runId) throws Exception {
        Capture title = run("eval", "--target=browser:auto", "--world=main", "--run-id=" + runId, "--expr=" + expr);
        assertEquals(0, title.code, title.err + title.out);
        return JSON.readTree(title.out).path("value").asText();
    }

    private Capture run(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
