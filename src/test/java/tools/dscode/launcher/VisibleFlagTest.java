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

/** Phase 7: a placement reports visible, hidden, and lost from the live element. */
class VisibleFlagTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void visibleAndHiddenAreReadBack() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("flags.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Flags</title></head><body>
                <button id="shown" data-pkb-handle="shown">Shown</button>
                <button id="gone" data-pkb-handle="gone" style="display:none">Gone</button>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=flags");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);

        Capture shown = run("label", "--text=On shown", "--handle=shown", "--target=browser:auto", "--run-id=flags");
        assertEquals(0, shown.code, shown.err + shown.out);
        JsonNode shownBody = JSON.readTree(shown.out);
        assertTrue(shownBody.path("ok").asBoolean(), shown.out);
        assertTrue(shownBody.has("visible") && shownBody.has("hidden") && shownBody.has("lost"), shown.out);
        assertTrue(shownBody.path("visible").asBoolean(), shown.out);
        assertFalse(shownBody.path("hidden").asBoolean(), shown.out);
        assertFalse(shownBody.path("lost").asBoolean(), shown.out);

        Capture hidden = run("label", "--text=On gone", "--handle=gone", "--target=browser:auto", "--run-id=flags");
        assertEquals(0, hidden.code, hidden.err + hidden.out);
        JsonNode hiddenBody = JSON.readTree(hidden.out);
        assertTrue(hiddenBody.path("ok").asBoolean(), hidden.out);
        assertTrue(hiddenBody.has("visible") && hiddenBody.has("hidden") && hiddenBody.has("lost"), hidden.out);
        assertFalse(hiddenBody.path("visible").asBoolean(), hidden.out);
        assertTrue(hiddenBody.path("hidden").asBoolean(), hidden.out);
        assertFalse(hiddenBody.path("lost").asBoolean(), hidden.out);

        Capture styles = run(
                "eval", "--target=browser:auto", "--world=main", "--run-id=flags",
                "--expr=(function(){return JSON.stringify({shown:getComputedStyle(document.getElementById('shown')).display,gone:getComputedStyle(document.getElementById('gone')).display});})()");
        assertEquals(0, styles.code, styles.err + styles.out);
        JsonNode css = JSON.readTree(JSON.readTree(styles.out).path("value").asText());
        assertEquals("none", css.path("gone").asText(), styles.out);
        assertFalse("none".equals(css.path("shown").asText()), styles.out);

        Capture missing = run("label", "--text=Missing", "--handle=absent", "--target=browser:auto", "--run-id=flags");
        assertEquals(2, missing.code, missing.err + missing.out);
        assertEquals("unknown handle: absent", JSON.readTree(missing.out).path("error").asText(), missing.out);
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
