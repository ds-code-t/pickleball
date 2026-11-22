package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.desktop.DesktopTree;
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

/** Phase 6: a compact snap is refs, and a later act uses one of those refs. */
class CompactSnapshotTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
        DesktopTree.resetForTests();
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void chromeCompactRefIsReusedByAct() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("save.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <button id="save">Save</button>
                <script>document.getElementById('save').onclick = () => { document.title = 'Saved'; };</script>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=snap-ref");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);

        Capture shot = run("snap", "--target=browser:auto", "--run-id=snap-ref");
        assertEquals(0, shot.code, shot.err + shot.out);
        JsonNode body = JSON.readTree(shot.out);
        assertEquals("compact", body.path("mode").asText(), shot.out);
        assertFalse(body.has("document"), shot.out);
        assertFalse(body.has("full"), shot.out);
        assertFalse(body.has("hits"), shot.out);
        assertFalse(shot.out.contains("<html"), shot.out);
        String line = lineNamed(body, "Save");
        String ref = refOn(line);
        assertTrue(line.startsWith("- button \"Save\" [ref=" + ref + "]"), line);

        Capture acted = run("act", "--target=" + ref, "--kind=click", "--run-id=snap-ref");
        assertEquals(0, acted.code, acted.err + acted.out);
        assertTrue(JSON.readTree(acted.out).path("applied").asBoolean(), acted.out);
        Capture title = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=snap-ref", "--expr=document.title");
        assertEquals(0, title.code, title.err + title.out);
        assertEquals("Saved", JSON.readTree(title.out).path("value").asText(), title.out);
    }

    @Test
    void desktopFixtureSnapRefBumpsTheTree() throws Exception {
        Path fixture = temp.resolve("notes.json");
        Files.writeString(fixture, """
                {"role":"window","name":"Notes","x":0,"y":0,"width":200,"height":100,"children":[
                  {"role":"button","name":"Save","x":10,"y":10,"width":40,"height":20}
                ]}
                """);
        Capture shot = run(
                "snap", "--target=desktop:app~Notes", "--fixture=" + fixture, "--run-id=snap-desk");
        assertEquals(0, shot.code, shot.err + shot.out);
        JsonNode body = JSON.readTree(shot.out);
        assertFalse(body.has("document"), shot.out);
        assertFalse(body.has("full"), shot.out);
        assertFalse(body.has("hits"), shot.out);
        String line = lineNamed(body, "Save");
        String ref = refOn(line);
        assertTrue(line.startsWith("- button \"Save\" [ref=" + ref + "]"), line);
        assertTrue(ref.startsWith("ref:desktop:0:"), ref);

        Capture acted = run("act", "--target=" + ref, "--kind=click", "--run-id=snap-desk");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode act = JSON.readTree(acted.out);
        assertFalse(act.path("applied").asBoolean(), acted.out);
        assertEquals("desktop:1", act.path("docVersion").asText(), acted.out);
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

    private static String lineNamed(JsonNode body, String name) {
        for (JsonNode line : body.path("items")) {
            String text = line.asText();
            if (text.startsWith("- button \"" + name + "\"")) return text;
        }
        throw new AssertionError("no line named " + name + " in " + body);
    }

    private static String refOn(String line) {
        int start = line.indexOf("[ref=");
        int end = line.indexOf(']', start);
        assertTrue(start > 0 && end > start, line);
        return line.substring(start + 5, end);
    }
}
