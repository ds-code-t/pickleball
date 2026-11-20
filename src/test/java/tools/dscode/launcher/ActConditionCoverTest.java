package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

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

/**
 * A condition click is one dispatch, then a wait. A covered target is not clicked.
 * Kills a second click when the page mark is still unchanged, and a click through an overlay.
 */
class ActConditionCoverTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void conditionClickDispatchesOnceAndWaitsPastTheSettle() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("condition.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <button id="go">Go</button>
                <script>
                window.clicks = 0;
                document.getElementById('go').onclick = function () {
                  window.clicks++;
                  setTimeout(function () { document.title = 'Done1'; }, 900);
                };
                </script>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=cond-once");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);
        assertEquals("Ready", eval("document.title", "cond-once"));
        assertEquals("0", eval("String(window.clicks)", "cond-once"));

        Capture acted = run(
                "act", "--target=browser:auto", "--query=Go", "--kind=click",
                "--condition=title=Done1", "--timeout=5000", "--run-id=cond-once");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode body = JSON.readTree(acted.out);
        assertTrue(body.path("ok").asBoolean(), acted.out);
        assertTrue(body.has("applied"), acted.out);
        assertTrue(body.path("applied").asBoolean(), acted.out);
        assertTrue(body.path("dispatched").asBoolean(), acted.out);
        assertEquals("title=Done1", body.path("condition").asText(), acted.out);
        assertEquals("Done1", body.path("title").asText(), acted.out);
        assertFalse(body.has("error"), acted.out);
        assertTrue(body.path("elapsedMs").asLong() >= 800, acted.out);
        assertEquals("1", eval("String(window.clicks)", "cond-once"), acted.out);
        assertEquals("Done1", eval("document.title", "cond-once"), acted.out);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void coveredTargetIsNotClickedUntilTheOverlayIsGone() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("covered.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <button id="go">Go</button>
                <div id="shade" role="dialog" aria-label="Shade"
                     style="position:fixed;inset:0;z-index:10;background:rgba(0,0,0,.15)"></div>
                <script>
                window.clicks = 0;
                document.getElementById('go').onclick = function () {
                  window.clicks++;
                  document.title = 'Clicked' + window.clicks;
                };
                </script>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=cover-once");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);
        assertEquals("0", eval("String(window.clicks)", "cover-once"));

        Capture blocked = run(
                "act", "--target=browser:auto", "--query=Go", "--kind=click", "--run-id=cover-once");
        assertEquals(1, blocked.code, blocked.err + blocked.out);
        JsonNode blockedBody = JSON.readTree(blocked.out);
        assertFalse(blockedBody.path("ok").asBoolean(), blocked.out);
        assertTrue(blockedBody.has("applied"), blocked.out);
        assertFalse(blockedBody.path("applied").asBoolean(), blocked.out);
        assertFalse(blockedBody.path("dispatched").asBoolean(), blocked.out);
        assertEquals("covered", blockedBody.path("error").asText(), blocked.out);
        assertEquals("dialog \"Shade\"", blockedBody.path("coveredBy").asText(), blocked.out);
        assertEquals("0", eval("String(window.clicks)", "cover-once"), blocked.out);
        assertEquals("Ready", eval("document.title", "cover-once"), blocked.out);

        assertEquals("gone", eval("document.getElementById('shade').remove(); 'gone'", "cover-once"));
        assertEquals("0", eval("String(window.clicks)", "cover-once"));

        Capture acted = run(
                "act", "--target=browser:auto", "--query=Go", "--kind=click", "--run-id=cover-once");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode body = JSON.readTree(acted.out);
        assertTrue(body.path("ok").asBoolean(), acted.out);
        assertTrue(body.path("applied").asBoolean(), acted.out);
        assertTrue(body.path("dispatched").asBoolean(), acted.out);
        assertFalse(body.has("error"), acted.out);
        assertEquals("1", eval("String(window.clicks)", "cover-once"), acted.out);
        assertEquals("Clicked1", eval("document.title", "cover-once"), acted.out);
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
