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

/** Phase 7: ask-and-wait returns the page answer. pin, toast, and checklist read back. */
class ConfirmTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PAGE_TEXT = """
            (function(){
              var text = document.body ? document.body.innerText : '';
              var layers = document.querySelectorAll('pkb-layer');
              for (var i = 0; i < layers.length; i++) {
                if (layers[i].shadowRoot) text += '\\n' + layers[i].shadowRoot.textContent;
              }
              return text;
            })()
            """;

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void covers_command_ask_and_wait() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("ask.html");
        Files.writeString(page, "<!DOCTYPE html><html><head><title>Ask</title></head><body>Ask</body></html>");
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=ask-wait");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);
        String armed = eval("""
                (function(){
                  function clickYes(){
                    var layers = document.querySelectorAll('pkb-layer');
                    for (var i = 0; i < layers.length; i++) {
                      var root = layers[i].shadowRoot;
                      if (!root) continue;
                      var buttons = root.querySelectorAll('button');
                      for (var j = 0; j < buttons.length; j++) {
                        if (buttons[j].textContent === 'Yes') { buttons[j].click(); return true; }
                      }
                    }
                    return false;
                  }
                  var timer = setInterval(function(){ if (clickYes()) clearInterval(timer); }, 50);
                  setTimeout(function(){ clearInterval(timer); }, 4000);
                  return 'armed';
                })()
                """, "ask-wait");
        assertEquals("armed", armed);

        Capture answered = run(
                "ask-and-wait", "--say=Ship it?", "--choices=Yes,No", "--timeout=5000", "--run-id=ask-wait");
        assertEquals(0, answered.code, answered.err + answered.out);
        JsonNode body = JSON.readTree(answered.out);
        assertEquals("ask-and-wait", body.path("op").asText(), answered.out);
        assertEquals("Yes", body.path("value").asText(), answered.out);
        assertTrue(body.path("applied").asBoolean(), answered.out);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void covers_command_pin() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("pin.html");
        Files.writeString(page, "<!DOCTYPE html><html><head><title>Card</title></head><body>Card</body></html>");
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=pin-note");
        assertEquals(0, started.code, started.err + started.out);
        Capture placed = run("pin", "--text=Pinned note", "--target=browser:auto", "--run-id=pin-note");
        assertEquals(0, placed.code, placed.err + placed.out);
        assertEquals("pin", JSON.readTree(placed.out).path("op").asText(), placed.out);
        String text = eval(PAGE_TEXT, "pin-note");
        assertTrue(text.contains("Pinned note"), text);
        assertFalse(text.contains("Other note"), text);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void covers_command_checklist() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("check.html");
        Files.writeString(page, "<!DOCTYPE html><html><head><title>Card</title></head><body>Card</body></html>");
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=check-note");
        assertEquals(0, started.code, started.err + started.out);
        Capture placed = run("checklist", "--items=Alpha|Beta", "--target=browser:auto", "--run-id=check-note");
        assertEquals(0, placed.code, placed.err + placed.out);
        assertEquals("checklist", JSON.readTree(placed.out).path("op").asText(), placed.out);
        String text = eval(PAGE_TEXT, "check-note");
        assertTrue(text.contains("Alpha"), text);
        assertTrue(text.contains("Beta"), text);
        assertFalse(text.contains("Gamma"), text);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void covers_command_toast() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("toast.html");
        Files.writeString(page, "<!DOCTYPE html><html><head><title>Toast</title></head><body>Toast</body></html>");
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=toast-note");
        assertEquals(0, started.code, started.err + started.out);
        Capture placed = run("toast", "--text=Toast note", "--target=browser:auto", "--run-id=toast-note");
        assertEquals(0, placed.code, placed.err + placed.out);
        JsonNode body = JSON.readTree(placed.out);
        assertTrue(body.path("transient").asBoolean(), placed.out);
        assertFalse(body.path("id").asText().isBlank(), placed.out);
        String shown = eval(PAGE_TEXT, "toast-note");
        assertTrue(shown.contains("Toast note"), shown);
        Thread.sleep(2000);
        String later = eval(PAGE_TEXT, "toast-note");
        assertFalse(later.contains("Toast note"), later);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void selftestPagesReadsTheCards() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Capture shot = run("selftest", "ui", "--component=pages");
        assertEquals(0, shot.code, shot.err + shot.out);
        JsonNode body = JSON.readTree(shot.out);
        assertTrue(body.path("ok").asBoolean(), shot.out);
        assertEquals("pages", body.path("component").asText(), shot.out);
        String pageText = body.path("pageText").asText("");
        for (String token : new String[]{"Pinned note", "Toast note", "Alpha", "Beta", "Waiting here"}) {
            boolean listed = false;
            for (JsonNode item : body.path("seen")) {
                if (token.equals(item.asText())) listed = true;
            }
            assertTrue(listed, token + " " + shot.out);
            assertTrue(pageText.contains(token), token + " " + pageText);
        }
    }

    private String eval(String expr, String runId) throws Exception {
        Capture title = run("eval", "--target=browser:auto", "--world=main", "--run-id=" + runId, "--expr=" + expr);
        assertEquals(0, title.code, title.err + title.out);
        return JSON.readTree(title.out).path("value").asText();
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
