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
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 7: ask-and-wait accepts only an answer for this question.
 * Kills "ask-and-wait ignores id".
 */
class AskIdTest {
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
    void secondAskIgnoresTheFirstAnswerAndTimesOut() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("ask.html");
        Files.writeString(page, "<!DOCTYPE html><html><head><title>Ask</title></head><body>Ask</body></html>");
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=ask-id");
        assertEquals(0, started.code, started.err + started.out);

        assertEquals("armed", eval(clicker("Ship it?", "Yes"), "ask-id"));
        Capture first = run(
                "ask-and-wait", "--say=Ship it?", "--choices=Yes,No", "--timeout=5000", "--run-id=ask-id");
        assertEquals(0, first.code, first.err + first.out);
        JsonNode yes = JSON.readTree(first.out);
        assertEquals("Yes", yes.path("value").asText(), first.out);
        String firstId = yes.path("id").asText();
        assertTrue(firstId.startsWith("q"), first.out);

        Capture[] held = new Capture[1];
        Thread waiter = new Thread(() -> held[0] = run(
                "ask-and-wait", "--say=Delete everything?", "--choices=Yes,No",
                "--timeout=1500", "--run-id=ask-id"), "ask-id-wait");
        waiter.start();
        Path log = temp.resolve(".pickleball").resolve("runs").resolve("ask-id").resolve("agent")
                .resolve("overlay-events.jsonl");
        String decoy = "{\"type\":\"answer\",\"id\":\"" + firstId + "\",\"value\":\"Yes\"}\n";
        Thread.sleep(200);
        Files.writeString(log, decoy, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        Thread.sleep(400);
        Files.writeString(log, decoy, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        waiter.join(10_000);
        assertFalse(waiter.isAlive(), "second ask did not finish");
        Capture second = held[0];
        assertEquals(1, second.code, second.err + second.out);
        JsonNode timed = JSON.readTree(second.out);
        assertEquals("timeout", timed.path("error").asText(), second.out);
        assertFalse(timed.has("value"), second.out);
        assertTrue(timed.path("elapsedMs").asLong(0) >= 1500, second.out);
        assertFalse(firstId.equals(timed.path("id").asText()), second.out);
        String gone = eval(PAGE_TEXT, "ask-id");
        assertFalse(gone.contains("Delete everything?"), gone);

        assertEquals("armed", eval(clicker("Delete everything?", "No"), "ask-id"));
        Capture third = run(
                "ask-and-wait", "--say=Delete everything?", "--choices=Yes,No",
                "--timeout=5000", "--run-id=ask-id");
        assertEquals(0, third.code, third.err + third.out);
        JsonNode no = JSON.readTree(third.out);
        assertEquals("No", no.path("value").asText(), third.out);
        assertFalse(firstId.equals(no.path("id").asText()), third.out);
    }

    private static String clicker(String question, String choice) {
        return """
                (function(){
                  function clickChoice(){
                    var layers = document.querySelectorAll('pkb-layer');
                    for (var i = 0; i < layers.length; i++) {
                      var root = layers[i].shadowRoot;
                      if (!root) continue;
                      var forms = root.querySelectorAll('form');
                      for (var f = 0; f < forms.length; f++) {
                        if ((forms[f].innerText || '').indexOf(%s) < 0) continue;
                        var buttons = forms[f].querySelectorAll('button');
                        for (var j = 0; j < buttons.length; j++) {
                          if (buttons[j].textContent === %s) { buttons[j].click(); return true; }
                        }
                      }
                    }
                    return false;
                  }
                  var timer = setInterval(function(){ if (clickChoice()) clearInterval(timer); }, 30);
                  setTimeout(function(){ clearInterval(timer); }, 4000);
                  return 'armed';
                })()
                """.formatted(jsString(question), jsString(choice));
    }

    private static String jsString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
