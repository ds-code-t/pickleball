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

/** Phase 5: replies name the frame, and frame --enter scopes find to that frame. */
class FrameInReplyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
        DesktopTree.resetForTests();
    }

    @Test
    void covers_command_frame() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"frame", temp.toString(), "--list", "--target=browser:auto", "--run-id=frame-missing"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        String out = stdout.toString(StandardCharsets.UTF_8);
        String err = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, out + err);
        assertTrue((out + err).contains("no live browser"), out + err);
        assertTrue(out.contains("\"applied\":false"), out);
        assertFalse(out.contains("\"ok\":true"), out);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void repliesCarryTheFrameAndEnterScopesFind() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("frames.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <button id="save">Save</button>
                <iframe id="box" srcdoc="&lt;button id=&quot;inside&quot;&gt;Inside&lt;/button&gt;"></iframe>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=frames");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);

        Capture found = run("find", "--target=browser:auto", "--query=Save", "--run-id=frames");
        assertEquals(0, found.code, found.err + found.out);
        JsonNode hit = JSON.readTree(found.out);
        String frame = hit.path("frame").asText("");
        assertFalse(frame.isBlank(), found.out);
        assertEquals("Save", hit.path("hits").get(0).path("name").asText(), found.out);

        Capture shot = run("snap", "--target=browser:auto", "--mode=compact", "--run-id=frames");
        assertEquals(0, shot.code, shot.err + shot.out);
        JsonNode snap = JSON.readTree(shot.out);
        assertEquals(frame, snap.path("frame").asText(""), shot.out);
        boolean same = false;
        for (JsonNode row : snap.path("hits")) {
            if ("Save".equals(row.path("name").asText())) {
                assertEquals(frame, row.path("frame").asText(""), shot.out);
                same = true;
            }
        }
        assertTrue(same, shot.out);

        Capture entered = run("frame", "--target=browser:auto", "--enter=box", "--run-id=frames");
        assertEquals(0, entered.code, entered.err + entered.out);
        assertEquals("box", JSON.readTree(entered.out).path("entered").asText(), entered.out);

        Capture outside = run("find", "--target=browser:auto", "--query=Save", "--run-id=frames");
        assertEquals(1, outside.code, outside.err + outside.out);
        assertTrue(outside.out.contains("not found"), outside.out);

        Capture inside = run("find", "--target=browser:auto", "--query=Inside", "--run-id=frames");
        assertEquals(0, inside.code, inside.err + inside.out);
        assertEquals("Inside", JSON.readTree(inside.out).path("hits").get(0).path("name").asText(), inside.out);

        Capture reset = run("frame", "--target=browser:auto", "--reset", "--run-id=frames");
        assertEquals(0, reset.code, reset.err + reset.out);
        assertEquals("", JSON.readTree(reset.out).path("entered").asText("missing"), reset.out);

        Capture again = run("find", "--target=browser:auto", "--query=Save", "--run-id=frames");
        assertEquals(0, again.code, again.err + again.out);
        assertEquals("Save", JSON.readTree(again.out).path("hits").get(0).path("name").asText(), again.out);

        Path fixture = temp.resolve("notes.json");
        Files.writeString(fixture, """
                {"role":"window","name":"Notes","x":0,"y":0,"width":200,"height":100,"children":[
                  {"role":"button","name":"Save","x":10,"y":10,"width":40,"height":20}
                ]}
                """);
        Capture desk = run(
                "find", "--target=desktop:app~Notes", "--fixture=" + fixture, "--query=Save", "--run-id=frames-desk");
        assertEquals(0, desk.code, desk.err + desk.out);
        assertEquals("Notes", JSON.readTree(desk.out).path("frame").asText(""), desk.out);
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
