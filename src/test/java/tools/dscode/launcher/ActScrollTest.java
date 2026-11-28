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
 * {@code pkb act --kind=scroll} through the production {@code WorkbenchAgentCommands} caller:
 * Human Mode allows it, the page or an element actually scrolls, and desktop reports a clear error.
 */
class ActScrollTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    /** Production path: a desktop fixture act of kind scroll is not {@code unknown kind}. */
    @Test
    void desktopScrollIsAClearErrorNotUnknownKind() throws Exception {
        Path fixture = temp.resolve("notes.json");
        Files.writeString(fixture, """
                {"role":"window","name":"Notes","x":0,"y":0,"width":200,"height":100,"children":[
                  {"role":"button","name":"Save","x":10,"y":10,"width":40,"height":20}
                ]}
                """);
        Capture acted = run(
                "act", "--target=desktop:app~Notes", "--fixture=" + fixture,
                "--query=Save", "--kind=scroll", "--run-id=desk-scroll");
        String all = acted.out + acted.err;
        assertTrue(acted.code != 0, all);
        assertFalse(all.contains("unknown kind"), all);
        assertTrue(all.contains("not supported on desktop") || all.contains("desktop"), all);
        assertTrue(all.contains("fix:"), all);
    }

    /**
     * Live Chrome: a human-locked {@code act --kind=scroll} changes {@code window.scrollY},
     * and a full-access (lock off) scroll changes an element's {@code scrollTop}.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void liveChromeScrollMovesThePageAndAnElement() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("scroll.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Scroll</title>
                <style>
                  body { margin: 0; }
                  #page { height: 4000px; }
                  #box { width: 200px; height: 120px; overflow: auto; }
                  #inner { height: 800px; }
                </style></head><body>
                <div id="page">
                  <button id="go">Go</button>
                  <div id="box" aria-label="Pane"><div id="inner">tall</div></div>
                </div>
                </body></html>""");
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless",
                "--run-id=act-scroll", "--human");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("human=on"), started.out);

        // Human lock refuses eval, so page movement is read from the act body and
        // confirmed with eval after the lock is turned off.
        Capture pageScroll = run("act", "--target=browser:auto", "--kind=scroll", "--dy=400",
                "--run-id=act-scroll");
        assertEquals(0, pageScroll.code, pageScroll.err + pageScroll.out);
        JsonNode pageBody = JSON.readTree(pageScroll.out);
        assertTrue(pageBody.path("applied").asBoolean(false), pageScroll.out);
        assertEquals("scroll", pageBody.path("kind").asText(), pageScroll.out);
        assertEquals(0, pageBody.path("dx").asInt(-1), pageScroll.out);
        assertEquals(400, pageBody.path("dy").asInt(-1), pageScroll.out);
        assertTrue(pageBody.path("scrollY").asDouble(0) > 0, "window.scrollY should move: " + pageScroll.out);
        assertFalse((pageScroll.out + pageScroll.err).contains("unknown kind"), pageScroll.out + pageScroll.err);

        Capture off = run("session-mode", "--human=off", "--run-id=act-scroll");
        assertEquals(0, off.code, off.err + off.out);
        Capture y = run("eval", "--target=browser:auto", "--world=main",
                "--expr=String(Math.round(window.scrollY))", "--run-id=act-scroll");
        assertEquals(0, y.code, y.err + y.out);
        assertTrue(Double.parseDouble(JSON.readTree(y.out).path("value").asText("0")) > 0, y.out);

        Capture beforeEl = run("eval", "--target=browser:auto", "--world=main",
                "--expr=String(document.getElementById('box').scrollTop)", "--run-id=act-scroll");
        assertEquals(0, beforeEl.code, beforeEl.err + beforeEl.out);
        double beforeTop = Double.parseDouble(JSON.readTree(beforeEl.out).path("value").asText("0"));

        Capture elScroll = run("act", "--target=browser:auto", "--kind=scroll", "--query=Pane",
                "--dx=0", "--dy=80", "--exposure=full", "--run-id=act-scroll");
        assertEquals(0, elScroll.code, elScroll.err + elScroll.out);
        JsonNode elBody = JSON.readTree(elScroll.out);
        assertTrue(elBody.path("applied").asBoolean(false), elScroll.out);
        assertEquals(80, elBody.path("dy").asInt(-1), elScroll.out);
        assertTrue(elBody.has("scrollTop") || elBody.has("scrollY"), elScroll.out);

        Capture afterEl = run("eval", "--target=browser:auto", "--world=main",
                "--expr=String(document.getElementById('box').scrollTop)", "--run-id=act-scroll");
        assertEquals(0, afterEl.code, afterEl.err + afterEl.out);
        double afterTop = Double.parseDouble(JSON.readTree(afterEl.out).path("value").asText("0"));
        assertTrue(afterTop > beforeTop, "element scrollTop should move: before=" + beforeTop
                + " after=" + afterTop + " act=" + elScroll.out + " eval=" + afterEl.out);
    }

    Capture run(String... args) {
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

    record Capture(int code, String out, String err) {
    }
}
