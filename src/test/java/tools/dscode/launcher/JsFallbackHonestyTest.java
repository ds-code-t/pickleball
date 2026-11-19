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
 * JS fallback click and select read the page after settle.
 * Kills "jsAct applied always true" and "JS select read-back taken before settle".
 */
class JsFallbackHonestyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        System.clearProperty("pkb_cdp_input");
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void jsClickAndSelectReadTheSettledPage() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("js-honest.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <button id="quiet">Quiet</button>
                <span id="mark">stay</span>
                <select id="micro"><option>S</option><option>A</option></select>
                <select id="later"><option>R</option><option>B</option></select>
                <script>
                document.getElementById('micro').onchange = function () {
                  var box = this;
                  Promise.resolve().then(function () { box.value = 'S'; });
                };
                document.getElementById('later').onchange = function () {
                  var box = this;
                  setTimeout(function () { box.value = 'R'; }, 0);
                };
                </script>
                </body></html>
                """);
        System.setProperty("pkb_cdp_input", "off");
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=js-honest");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);

        Capture clicked = run("act", "--target=browser:auto", "--query=Quiet", "--kind=click", "--run-id=js-honest");
        assertEquals(0, clicked.code, clicked.err + clicked.out);
        JsonNode click = JSON.readTree(clicked.out);
        assertTrue(click.path("ok").asBoolean(), clicked.out);
        assertFalse(click.path("applied").asBoolean(), clicked.out);
        assertEquals("js", click.path("used").asText(), clicked.out);
        assertTrue(click.path("dispatched").asBoolean(), clicked.out);
        assertFalse(click.has("error"), clicked.out);
        assertEquals("Ready|stay", eval("document.title + '|' + document.getElementById('mark').textContent"), clicked.out);

        Capture micro = run("act", "--target=browser:auto", "--query=micro", "--kind=select", "--value=A", "--run-id=js-honest");
        assertEquals(1, micro.code, micro.err + micro.out);
        JsonNode microBody = JSON.readTree(micro.out);
        assertFalse(microBody.path("applied").asBoolean(), micro.out);
        assertEquals("js", microBody.path("used").asText(), micro.out);
        assertEquals("not applied", microBody.path("error").asText(), micro.out);
        assertEquals("S", eval("document.getElementById('micro').value"), micro.out);

        Capture later = run("act", "--target=browser:auto", "--query=later", "--kind=select", "--value=B", "--run-id=js-honest");
        assertEquals(1, later.code, later.err + later.out);
        JsonNode laterBody = JSON.readTree(later.out);
        assertFalse(laterBody.path("applied").asBoolean(), later.out);
        assertEquals("js", laterBody.path("used").asText(), later.out);
        assertEquals("not applied", laterBody.path("error").asText(), later.out);
        assertEquals("R", eval("document.getElementById('later').value"), later.out);
    }

    private String eval(String expression) throws Exception {
        Capture read = run("eval", "--target=browser:auto", "--world=main", "--run-id=js-honest", "--expr=" + expression);
        assertEquals(0, read.code, read.err + read.out);
        return JSON.readTree(read.out).path("value").asText("");
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
