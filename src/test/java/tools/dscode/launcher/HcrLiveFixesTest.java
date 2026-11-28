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
 * PKB-HCR 2026-11-28 live-repro items fixed from code reading: 10 (block overlay and typing), 16 (frame with no
 * --target), 20 (Enter in the panel reported not applied), 23 (panel-open tab name and minimized state, panel and
 * dock place across navigate).
 */
class HcrLiveFixesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PAGE = "<!DOCTYPE html><html><head><title>HCR</title></head><body>"
            + "<button id=\"go\">Go</button><input id=\"note\" aria-label=\"Note\">"
            + "<iframe id=\"inner\" name=\"inner\" srcdoc=\"<button>Inner</button>\"></iframe></body></html>";

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    /** HCR-10: a --block=true overlay refused clicks but a typed act went through with applied=true. */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void blockOverlayRefusesTypingIntoTheBlockedField() throws Exception {
        start();
        JsonNode found = JSON.readTree(jsonLine(agent("find", "--target=browser:auto", "--query=Note").out));
        String id = found.path("items").path(0).path("handle").asText("");
        assertFalse(id.isBlank(), found.toString());
        Capture overlay = agent("overlay", "--handle=" + id, "--text=Blocked", "--block=true");
        assertEquals(0, overlay.code, overlay.err + overlay.out);

        Capture typed = helper("act", "--target=browser:auto", "--kind=type", "--query=Note", "--value=typed-through");
        assertTrue(typed.code != 0, "typing went through a block overlay: " + typed.out);
        assertTrue((typed.out + typed.err).contains("covered"), typed.out + typed.err);
        Capture value = agent("eval", "--target=browser:auto", "--expr=document.getElementById('note').value", "--world=main");
        assertFalse(value.out.contains("typed-through"), value.out);
    }

    /** HCR-16: frame --list/--enter/--reset failed "no live browser" while a live start was up. */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void frameVerbsUseTheLiveBrowserWithoutTarget() throws Exception {
        start();
        Capture listed = agent("frame", "--list");
        assertEquals(0, listed.code, listed.err + listed.out);
        assertFalse(listed.out.contains("no live browser"), listed.out);
        assertTrue(listed.out.contains("inner"), listed.out);
        Capture entered = agent("frame", "--enter=inner");
        assertEquals(0, entered.code, entered.err + entered.out);
        Capture reset = agent("frame", "--reset");
        assertEquals(0, reset.code, reset.err + reset.out);
    }

    /** HCR-20: Enter in the panel composer sent the message but act replied "not applied". */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void enterInThePanelComposerIsApplied() throws Exception {
        start();
        assertEquals(0, agent("panel-open", "--tab=chat").code);
        assertEquals(0, helper("act", "--target=browser:auto", "--kind=type", "--purpose=message", "--query=Message",
                "--value=sent-with-enter").code);
        Capture pressed = helper("act", "--target=browser:auto", "--kind=press", "--purpose=message", "--query=Message",
                "--value=Enter");
        assertEquals(0, pressed.code, "Enter sent the chat line but act said: " + pressed.out + pressed.err);
        assertFalse(pressed.out.contains("not applied"), pressed.out);
        JsonNode history = JSON.readTree(agent("chat-history", "--after=0").out);
        assertTrue(history.toString().contains("sent-with-enter"), history.toString());
    }

    /** HCR-23: --tab=guide answered steps, --tab=chat left the panel minimized, navigate moved panel and dock. */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void panelOpenTabNamesUnminimizeAndNavigateKeepsPanelAndDock() throws Exception {
        Path page = start();
        JsonNode guide = JSON.readTree(jsonLine(agent("panel-open", "--tab=guide").out));
        assertEquals("guide", guide.path("tab").asText(), guide.toString());

        assertEquals(0, agent("panel-open", "--minimized=true").code);
        JsonNode chat = JSON.readTree(jsonLine(agent("panel-open", "--tab=chat").out));
        assertEquals("chat", chat.path("tab").asText(), chat.toString());
        assertFalse(chat.path("panel").path("min").asBoolean(true), "--tab=chat left the panel minimized: " + chat);

        assertEquals(0, agent("panel-open", "--x=300", "--y=180", "--collapsed=toolbar").code);
        Capture moved = agent("navigate", "--url=" + page.toUri());
        assertEquals(0, moved.code, moved.err + moved.out);
        JsonNode state = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            state = JSON.readTree(jsonLine(agent("panel-state").out));
            if (state.toString().contains("\"x\":300")) break;
            Thread.sleep(250);
        }
        assertTrue(state.toString().contains("\"x\":300"), "panel moved by navigate: " + state);
        assertTrue(state.toString().contains("\"collapsed\":true"), "dock reset by navigate: " + state);
    }

    private Path start() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("hcr.html");
        Files.writeString(page, PAGE);
        Capture started = agent("start", "--url=" + page.toUri(), "--browser=chrome-headless");
        assertEquals(0, started.code, started.err + started.out);
        assertEquals(0, helper("session-mode", "--human=on").code);
        return page;
    }

    /** The first line that is a JSON object. */
    private static String jsonLine(String out) {
        for (String line : out.split("\\R")) {
            if (line.trim().startsWith("{")) return line.trim();
        }
        return out;
    }

    private Capture agent(String... args) {
        return run("agent", args);
    }

    private Capture helper(String... args) {
        return run("helper", args);
    }

    private Capture run(String runId, String... args) {
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

    private record Capture(int code, String out, String err) {
    }
}
