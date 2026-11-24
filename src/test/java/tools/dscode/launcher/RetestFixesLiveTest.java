package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * Fixes from the 2026-11-24 two-bot retest, live on one start session with the panel open:
 * an agent under run id {@code agent} and a human-locked helper under run id {@code human}.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class RetestFixesLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path temp;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("retest.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Retest</title></head><body>
                <label>Email <input id="email" aria-label="Email"></label>
                <button id="go" onclick="document.getElementById('status').textContent='Went'">Go</button>
                <label>Upload File <input id="upload-file" type="file" aria-label="Upload File"></label>
                <p id="status">Ready</p>
                </body></html>""");
        Capture started = agent("start", "--url=" + page.toUri(), "--browser=chrome-headless");
        assertEquals(0, started.code, started.err + started.out);
        assertEquals(0, agent("panel-open", "--tab=chat").code);
        assertEquals(0, human("session-mode", "--human=on").code);
    }

    @AfterAll
    static void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    /** R3: a minimized panel reads as collapsed, its composer is hidden and refused, and the person can reopen it. */
    @Test
    void minimizedPanelIsCollapsedAndReopensFromThePage() throws Exception {
        assertEquals(0, human("act", "--target=browser:auto", "--kind=click", "--query=Minimize").code);
        JsonNode snap = JSON.readTree(agent("snap", "--target=browser:auto", "--exposure=full").out);
        assertEquals("collapsed", snap.path("panel").asText(), snap.toString());
        JsonNode message = row(JSON.readTree(agent("find", "--target=browser:auto", "--query=Message", "--exposure=full").out), "Message");
        assertFalse(message.path("visible").asBoolean(true), message.toString());

        Capture typed = human("act", "--target=browser:auto", "--kind=type", "--purpose=message", "--query=Message", "--value=hidden-draft");
        assertEquals(1, typed.code, typed.out + typed.err);
        assertTrue(typed.out.contains("hidden"), typed.out);

        Capture reopened = human("act", "--target=browser:auto", "--kind=click", "--purpose=reopen", "--query=Open agent panel");
        assertEquals(0, reopened.code, reopened.out + reopened.err);
        assertTrue(JSON.readTree(reopened.out).path("applied").asBoolean(false), reopened.out);
        JsonNode after = JSON.readTree(agent("snap", "--target=browser:auto", "--exposure=full").out);
        assertEquals("open", after.path("panel").asText(), after.toString());
        JsonNode shown = null;
        for (int i = 0; i < 10 && (shown == null || !row(shown, "Message").path("visible").asBoolean(false)); i++) {
            if (shown != null) Thread.sleep(200);
            shown = JSON.readTree(agent("find", "--target=browser:auto", "--query=Message", "--exposure=full").out);
        }
        assertTrue(row(shown, "Message").path("visible").asBoolean(false), shown.toString());
    }

    /** R4: a person's click on a file input (a human-locked act) offers "Ask agent to attach a file"; it posts upload-request. */
    @Test
    void personsFileInputClickOffersTheUploadHandOff() throws Exception {
        String cursor = JSON.readTree(agent("ui-poll", "--after=0").out).path("next").asText();
        human("act", "--target=browser:auto", "--kind=click", "--query=Upload File");
        JsonNode offer = JSON.readTree(human("find", "--target=browser:auto", "--query=Ask agent to attach a file").out);
        assertTrue(offer.path("ok").asBoolean(false), offer.toString());
        Capture asked = human("act", "--target=browser:auto", "--kind=click", "--query=Ask agent to attach a file");
        assertEquals(0, asked.code, asked.out + asked.err);
        JsonNode polled = JSON.readTree(agent("ui-poll", "--after=" + cursor, "--wait=5").out);
        assertTrue(polled.path("items").toString().contains("upload-request"), polled.toString());
        assertTrue(polled.path("items").toString().contains("#upload-file"), polled.toString());
    }

    static JsonNode row(JsonNode snap, String name) {
        for (JsonNode item : snap.path("items")) if (name.equals(item.path("name").asText())) return item;
        return JSON.createObjectNode();
    }

    static Capture agent(String... args) {
        return run("agent", args);
    }

    static Capture human(String... args) {
        return run("human", args);
    }

    static Capture run(String runId, String... args) {
        String[] command = new String[args.length + 2];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        command[command.length - 1] = "--run-id=" + runId;
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
