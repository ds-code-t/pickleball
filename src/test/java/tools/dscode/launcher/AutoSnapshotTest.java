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

/**
 * Phase 6: {@code --snap=auto} keeps a pre/post compact snapshot on disk and returns its id.
 * Kills "skip the post tree" and "inline the compact lines in the reply".
 */
class AutoSnapshotTest {
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
    void actAutoSnapshotKeepsTheTreeFromBeforeAndAfterTheClick() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("save.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <button id="save">Save</button>
                <span id="n">0</span>
                <script>
                document.getElementById('save').onclick = () => {
                  const n = document.getElementById('n');
                  n.textContent = String(Number(n.textContent) + 1);
                  document.getElementById('save').textContent = 'Saved';
                };
                </script>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=auto-act");
        assertEquals(0, started.code, started.err + started.out);

        Capture acted = run(
                "act", "--target=browser:auto", "--query=Save", "--kind=click",
                "--snap=auto", "--run-id=auto-act");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode body = JSON.readTree(acted.out);
        assertTrue(body.path("applied").asBoolean(), acted.out);
        assertTrue(body.path("dispatched").asBoolean(), acted.out);
        assertFalse(body.has("lines"), acted.out);
        assertFalse(acted.out.contains("## before"), acted.out);
        String id = body.path("snapshot").asText("");
        assertTrue(id.startsWith("auto-auto-act-"), acted.out);
        String file = Files.readString(temp.resolve(".pickleball").resolve("agent-full").resolve(id + ".txt"));
        int split = file.indexOf("\n## after\n");
        assertTrue(split > 0, file);
        String pre = file.substring(0, split);
        String post = file.substring(split);
        assertTrue(pre.startsWith("## before\n"), file);
        assertTrue(pre.contains("- button \"Save\" [ref="), file);
        assertFalse(pre.contains("- button \"Saved\" [ref="), file);
        assertTrue(post.contains("- button \"Saved\" [ref="), file);
        assertFalse(post.contains("- button \"Save\" [ref="), file);

        Capture count = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=auto-act",
                "--expr=document.getElementById('n').textContent");
        assertEquals(0, count.code, count.err + count.out);
        assertEquals("1", JSON.readTree(count.out).path("value").asText(), count.out);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void waitAutoSnapshotKeepsTheTreeFromBeforeAndAfterTheCondition() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("later.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <button id="go">Waiting</button>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=auto-wait");
        assertEquals(0, started.code, started.err + started.out);
        Capture armed = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=auto-wait",
                "--expr=setTimeout(function(){document.getElementById('go').textContent='Done';document.title='Later'},1200); 'armed'");
        assertEquals(0, armed.code, armed.err + armed.out);

        Capture waited = run(
                "wait", "--target=browser:auto", "--condition=title=Later",
                "--timeout=5000", "--snap=auto", "--run-id=auto-wait");
        assertEquals(0, waited.code, waited.err + waited.out);
        JsonNode body = JSON.readTree(waited.out);
        assertEquals("Later", body.path("title").asText(), waited.out);
        assertTrue(body.path("elapsedMs").asLong(0) >= 700, waited.out);
        assertFalse(body.has("lines"), waited.out);
        assertFalse(waited.out.contains("## before"), waited.out);
        String id = body.path("snapshot").asText("");
        assertTrue(id.startsWith("auto-auto-wait-"), waited.out);
        String file = Files.readString(temp.resolve(".pickleball").resolve("agent-full").resolve(id + ".txt"));
        int split = file.indexOf("\n## after\n");
        assertTrue(split > 0, file);
        String pre = file.substring(0, split);
        String post = file.substring(split);
        assertTrue(pre.contains("- button \"Waiting\" [ref="), file);
        assertFalse(pre.contains("- button \"Done\" [ref="), file);
        assertTrue(post.contains("- button \"Done\" [ref="), file);
        assertFalse(post.contains("- button \"Waiting\" [ref="), file);
    }

    @Test
    void desktopActAutoSnapshotNamesTheVersionAfterTheClick() throws Exception {
        Path fixture = temp.resolve("notes.json");
        Files.writeString(fixture, """
                {"role":"window","name":"Notes","x":0,"y":0,"width":200,"height":100,"children":[
                  {"role":"button","name":"Save","x":10,"y":10,"width":40,"height":20}
                ]}
                """);
        Capture acted = run(
                "act", "--target=desktop:app~Notes", "--fixture=" + fixture,
                "--query=Save", "--kind=click", "--snap=auto", "--run-id=auto-desk");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode body = JSON.readTree(acted.out);
        assertFalse(body.path("applied").asBoolean(), acted.out);
        assertFalse(body.has("lines"), acted.out);
        String id = body.path("snapshot").asText("");
        assertTrue(id.startsWith("auto-auto-desk-"), acted.out);
        String file = Files.readString(temp.resolve(".pickleball").resolve("agent-full").resolve(id + ".txt"));
        int split = file.indexOf("\n## after\n");
        assertTrue(split > 0, file);
        String pre = file.substring(0, split);
        String post = file.substring(split);
        assertTrue(pre.contains("- button \"Save\" [ref=ref:desktop:0:"), file);
        assertTrue(post.contains("- button \"Save\" [ref=ref:desktop:1:"), file);
        assertFalse(post.contains("ref:desktop:0:"), file);
    }

    @Test
    void desktopWaitAutoSnapshotWritesBothHalves() throws Exception {
        Path fixture = temp.resolve("wait.json");
        Files.writeString(fixture, """
                {"role":"window","name":"Notes","x":0,"y":0,"width":200,"height":100,"children":[
                  {"role":"button","name":"Save","x":10,"y":10,"width":40,"height":20}
                ]}
                """);
        Capture waited = run(
                "wait", "--target=desktop:app~Notes", "--fixture=" + fixture,
                "--snap=auto", "--run-id=auto-desk-wait");
        assertEquals(0, waited.code, waited.err + waited.out);
        JsonNode body = JSON.readTree(waited.out);
        assertFalse(body.has("lines"), waited.out);
        assertFalse(body.has("snap"), waited.out);
        String id = body.path("snapshot").asText("");
        assertTrue(id.startsWith("auto-auto-desk-wait-"), waited.out);
        String file = Files.readString(temp.resolve(".pickleball").resolve("agent-full").resolve(id + ".txt"));
        int split = file.indexOf("\n## after\n");
        assertTrue(split > 0, file);
        assertTrue(file.substring(0, split).contains("- button \"Save\" [ref="), file);
        assertTrue(file.substring(split).contains("- button \"Save\" [ref="), file);
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
