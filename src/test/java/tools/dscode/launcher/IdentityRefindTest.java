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

/** D6: a stale ref is re-found through its identity chain only when asked, and a recorded chain replays. */
class IdentityRefindTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void covers_flag_refind() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        start();
        String ref = saveRef();
        rerender();
        Capture stale = agent("act", "--target=" + ref, "--kind=click", "--run-id=d6");
        assertEquals(1, stale.code, stale.out + stale.err);
        assertEquals("stale_ref", JSON.readTree(stale.out).path("error").asText(), "the default stays stale: " + stale.out);
        assertEquals("0", eval("String(window.__n||0)"));

        Capture refound = agent("act", "--target=" + ref, "--kind=click", "--refind=chain", "--run-id=d6");
        assertEquals(0, refound.code, refound.out + refound.err);
        JsonNode body = JSON.readTree(refound.out);
        assertTrue(body.path("applied").asBoolean(), refound.out);
        assertEquals("stable", body.path("refound").path("matched").asText(), refound.out);
        assertEquals("id=save", body.path("refound").path("locator").asText(), refound.out);
        assertEquals(0, body.path("refound").path("index").asInt(), refound.out);
        assertEquals("stale-ref", body.path("refound").path("why").asText(), refound.out);
        assertEquals("1", eval("String(window.__n||0)"), "the re-rendered button was clicked");

        String gone = saveRef();
        eval("document.getElementById('save').remove()");
        Capture missing = agent("act", "--target=" + gone, "--kind=click", "--refind=chain", "--run-id=d6");
        assertEquals(1, missing.code, missing.out + missing.err);
        assertEquals("stale_ref", JSON.readTree(missing.out).path("error").asText(), "nothing to re-find stays stale: " + missing.out);

        Capture bad = agent("act", "--target=browser:auto", "--kind=click", "--query=Other", "--refind=maybe", "--run-id=d6");
        assertEquals(2, bad.code, bad.out + bad.err);
        assertTrue(bad.err.contains("error: --refind must be chain or off"), bad.err);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void covers_flag_identity() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        start();
        Path recorded = temp.resolve("save.chain.json");
        Files.writeString(recorded, "[{\"kind\":\"stable\",\"locator\":\"id=save\"},"
                + "{\"kind\":\"xpath\",\"locator\":\"xpath=/html[1]/body[1]/button[2]\"}]");
        rerender();
        Capture replayed = agent("act", "--target=browser:auto", "--kind=click", "--identity=" + recorded, "--run-id=d6");
        assertEquals(0, replayed.code, replayed.out + replayed.err);
        JsonNode body = JSON.readTree(replayed.out);
        assertEquals("stable", body.path("refound").path("matched").asText(), replayed.out);
        assertEquals("identity", body.path("refound").path("why").asText(), replayed.out);
        assertEquals("1", eval("String(window.__n||0)"));

        eval("document.getElementById('save').removeAttribute('id')");
        Capture positional = agent("act", "--target=browser:auto", "--kind=click", "--identity=" + recorded, "--run-id=d6");
        assertEquals(0, positional.code, positional.out + positional.err);
        JsonNode last = JSON.readTree(positional.out).path("refound");
        assertEquals("xpath", last.path("matched").asText(), "positional XPath only after the stable id fails: " + positional.out);
        assertEquals(1, last.path("index").asInt(), positional.out);
        assertEquals("2", eval("String(window.__n||0)"));

        Capture nothing = agent("act", "--target=browser:auto", "--kind=click",
                "--identity=[{\"kind\":\"stable\",\"locator\":\"id=nope\"}]", "--run-id=d6");
        assertEquals(1, nothing.code, nothing.out + nothing.err);
        assertFalse(JSON.readTree(nothing.out).path("applied").asBoolean(), nothing.out);
        assertEquals("2", eval("String(window.__n||0)"));

        Capture invalid = agent("act", "--target=browser:auto", "--kind=click", "--identity=" + temp.resolve("missing.json"), "--run-id=d6");
        assertEquals(2, invalid.code, invalid.out + invalid.err);
        assertTrue(invalid.err.contains("error: --identity is not an identity chain"), invalid.err);
    }

    private void start() throws Exception {
        Path page = temp.resolve("d6.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>D6</title></head><body>
                <button id="other">Other</button>
                <button id="save">Save</button>
                </body></html>""");
        Capture started = agent("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=d6");
        assertEquals(0, started.code, started.err + started.out);
    }

    private void rerender() throws Exception {
        eval("document.getElementById('save').outerHTML='<button id=\"save\">Save</button>';"
                + "document.getElementById('save').addEventListener('click', function(){window.__n=(window.__n||0)+1; document.title='Saved'+window.__n;});");
    }

    private String saveRef() throws Exception {
        Capture shot = agent("snap", "--target=browser:auto", "--run-id=d6");
        assertEquals(0, shot.code, shot.err + shot.out);
        for (JsonNode line : JSON.readTree(shot.out).path("items")) {
            String text = line.asText();
            int start = text.indexOf("[ref=");
            if (text.contains("\"Save\"") && start > 0) return text.substring(start + 5, text.indexOf(']', start));
        }
        throw new AssertionError("no Save ref in " + shot.out);
    }

    private String eval(String expression) throws Exception {
        Capture read = agent("eval", "--target=browser:auto", "--world=main", "--run-id=d6", "--expr=" + expression);
        assertEquals(0, read.code, read.err + read.out);
        return JSON.readTree(read.out).path("value").asText("");
    }

    private Capture agent(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
