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

/** Phase 6: act and wait with snap=diff report the change that landed. */
class SnapshotDiffOnActTest {
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
    void actSnapDiffReadsTheTitleChange() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("save.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <button id="save">Save</button>
                <script>document.getElementById('save').onclick = () => { document.title = 'Saved'; };</script>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=snap-diff");
        assertEquals(0, started.code, started.err + started.out);

        Capture acted = run(
                "act", "--target=browser:auto", "--query=Save", "--kind=click",
                "--snap=diff", "--run-id=snap-diff");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode body = JSON.readTree(acted.out);
        assertTrue(body.path("applied").asBoolean(), acted.out);
        assertEquals("Ready -> Saved", body.path("snap").path("title").asText(), acted.out);
        assertTrue(body.path("snap").path("changed").asBoolean(), acted.out);
        assertTrue(body.path("snap").path("docVersion").asText("").contains(" -> "), acted.out);
        Capture title = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=snap-diff", "--expr=document.title");
        assertEquals(0, title.code, title.err + title.out);
        assertEquals("Saved", JSON.readTree(title.out).path("value").asText(), title.out);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void waitSnapDiffReadsTheTitleChange() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("later.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body><p>Stay</p></body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=snap-wait");
        assertEquals(0, started.code, started.err + started.out);
        Capture before = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=snap-wait", "--expr=document.title");
        assertEquals(0, before.code, before.err + before.out);
        assertEquals("Ready", JSON.readTree(before.out).path("value").asText(), before.out);
        Capture armed = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=snap-wait",
                "--expr=setTimeout(function(){document.title='Later'},1500); 'armed'");
        assertEquals(0, armed.code, armed.err + armed.out);

        Capture waited = run(
                "wait", "--target=browser:auto", "--condition=title=Later",
                "--timeout=5000", "--snap=diff", "--run-id=snap-wait");
        assertEquals(0, waited.code, waited.err + waited.out);
        JsonNode body = JSON.readTree(waited.out);
        assertEquals("Ready -> Later", body.path("snap").path("title").asText(), waited.out);
        assertTrue(body.path("snap").path("changed").asBoolean(), waited.out);
        Capture title = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=snap-wait", "--expr=document.title");
        assertEquals(0, title.code, title.err + title.out);
        assertEquals("Later", JSON.readTree(title.out).path("value").asText(), title.out);
    }

    @Test
    void desktopActSnapDiffReportsTheVersionBump() throws Exception {
        Path fixture = temp.resolve("notes.json");
        Files.writeString(fixture, """
                {"role":"window","name":"Notes","x":0,"y":0,"width":200,"height":100,"children":[
                  {"role":"button","name":"Save","x":10,"y":10,"width":40,"height":20}
                ]}
                """);
        Capture acted = run(
                "act", "--target=desktop:app~Notes", "--fixture=" + fixture,
                "--query=Save", "--kind=click", "--snap=diff", "--run-id=snap-desk-diff");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode body = JSON.readTree(acted.out);
        assertFalse(body.path("applied").asBoolean(), acted.out);
        assertEquals("desktop:0 -> desktop:1", body.path("snap").path("docVersion").asText(), acted.out);
        assertTrue(body.path("snap").path("changed").asBoolean(), acted.out);
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
