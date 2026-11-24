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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A blocking ask on the start host must leave doctor, snap, and act responsive,
 * and the wait must still return the click.
 */
class WaitLaneLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLICK_YES = """
            (function(){
              function clickYes(){
                var layers = document.querySelectorAll('pkb-layer');
                for (var i = 0; i < layers.length; i++) {
                  var root = layers[i].shadowRoot;
                  if (!root) continue;
                  var buttons = root.querySelectorAll('button');
                  for (var j = 0; j < buttons.length; j++) {
                    if (buttons[j].textContent === 'Yes') { buttons[j].click(); return 'yes'; }
                  }
                }
                return 'no';
              }
              return clickYes();
            })()
            """;

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    void covers_command_answer_wait() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"answer-wait", temp.toString(), "--run-id=answer-wait-usage"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(2, code, stderr.toString(StandardCharsets.UTF_8) + out);
        assertTrue(out.contains("answer-wait requires --id"), out);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void secondClientStaysHealthyDuringALongWait() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("wait.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Wait</title></head><body>
                <button id="go" onclick="this.textContent='Went'">Go</button>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=wait-lane");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Capture> blocking = pool.submit(() -> run(
                    "ask-and-wait", "--say=Ship it?", "--choices=Yes,No",
                    "--timeout=20000", "--run-id=wait-lane"));
            awaitWait(blocking);
            Capture snap = timed("snap", "--target=browser:auto", "--run-id=wait-lane");
            assertEquals(0, snap.code, snap.err + snap.out);
            assertFalse(snap.out.contains("unreachable"), snap.out);
            Capture act = timed("act", "--target=browser:auto", "--query=Go", "--kind=click", "--run-id=wait-lane");
            assertFalse(act.out.contains("unreachable"), act.out);
            assertTrue(act.millis < 2000, "act took " + act.millis + " ms " + act.out);
            String label = eval("document.getElementById('go').textContent", "wait-lane");
            assertEquals("Went", label);
            clickYesUntil(blocking, null);
            Capture answered = blocking.get(15, TimeUnit.SECONDS);
            assertEquals(0, answered.code, answered.err + answered.out);
            JsonNode body = JSON.readTree(answered.out);
            assertEquals("ask-and-wait", body.path("op").asText(), answered.out);
            assertEquals("Yes", body.path("value").asText(), answered.out);
            assertTrue(body.path("applied").asBoolean(), answered.out);

            long asyncStarted = System.nanoTime();
            Capture async = run(
                    "ask-and-wait", "--say=Again?", "--choices=Yes,No", "--async", "--run-id=wait-lane");
            long asyncMillis = (System.nanoTime() - asyncStarted) / 1_000_000L;
            assertEquals(0, async.code, async.err + async.out);
            assertTrue(asyncMillis < 3000, "async ask took " + asyncMillis + " ms");
            JsonNode placed = JSON.readTree(async.out);
            assertEquals("ask-and-wait", placed.path("op").asText(), async.out);
            assertTrue(placed.path("async").asBoolean(), async.out);
            String id = placed.path("id").asText();
            assertFalse(id.isBlank(), async.out);

            Future<Capture> waiting = pool.submit(() -> run(
                    "answer-wait", "--id=" + id, "--timeout=8s", "--run-id=wait-lane"));
            awaitWait(waiting);
            clickYesUntil(waiting, id);
            Capture choice = waiting.get(15, TimeUnit.SECONDS);
            assertEquals(0, choice.code, choice.err + choice.out);
            JsonNode answer = JSON.readTree(choice.out);
            assertEquals("answer-wait", answer.path("op").asText(), choice.out);
            assertEquals(id, answer.path("id").asText(), choice.out);
            assertEquals("Yes", answer.path("value").asText(), choice.out);
            assertEquals("Yes", answer.path("choice").asText(), choice.out);
            assertTrue(answer.path("applied").asBoolean(), choice.out);

            Capture second = run(
                    "ask-and-wait", "--say=Poll?", "--choices=Yes,No", "--async", "--run-id=wait-lane");
            assertEquals(0, second.code, second.err + second.out);
            String pollId = JSON.readTree(second.out).path("id").asText();
            assertFalse(pollId.isBlank(), second.out);
            Future<Capture> polling = pool.submit(() -> run(
                    "ui-poll", "--id=" + pollId, "--wait=12", "--run-id=wait-lane"));
            awaitWait(polling);
            clickYesUntil(polling, pollId);
            Capture polled = polling.get(20, TimeUnit.SECONDS);
            assertEquals(0, polled.code, polled.err + polled.out);
            JsonNode polledBody = JSON.readTree(polled.out);
            assertEquals(pollId, polledBody.path("id").asText(), polled.out);
            assertEquals("Yes", polledBody.path("value").asText(), polled.out);
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitWait(Future<Capture> waiter) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        String last = "";
        while (System.nanoTime() < deadline) {
            if (waiter.isDone()) {
                Capture failed = waiter.get();
                throw new AssertionError("wait ended before doctor saw it: " + failed.out + failed.err);
            }
            Capture doctor = timed("doctor", "--run-id=wait-lane");
            last = doctor.out;
            assertEquals(0, doctor.code, doctor.err + doctor.out);
            assertTrue(doctor.millis < 2000, "doctor took " + doctor.millis + " ms\n" + doctor.out);
            assertFalse(doctor.out.contains("unreachable"), doctor.out);
            assertFalse(doctor.out.contains("health=down"), doctor.out);
            if (doctor.out.contains("health=ok") && doctor.out.matches("(?s).*waits=[1-9].*")) return;
            Thread.sleep(50);
        }
        throw new AssertionError("doctor never reported an active wait\n" + last);
    }

    private void clickYesUntil(Future<Capture> waiter, String id) throws Exception {
        String script = id == null || id.isBlank() ? CLICK_YES : clickCard(id);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String last = "";
        while (!waiter.isDone() && System.nanoTime() < deadline) {
            last = eval(script, "wait-lane");
            if ("yes".equals(last)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("did not click Yes on " + (id == null ? "the open card" : id) + " last=" + last);
    }

    private static String clickCard(String id) {
        String quoted = id.replace("\\", "\\\\").replace("'", "\\'");
        return "(function(){"
                + "var layers=document.querySelectorAll('pkb-layer');"
                + "for(var i=0;i<layers.length;i++){"
                + "var root=layers[i].shadowRoot; if(!root) continue;"
                + "var card=root.querySelector('[data-pkb-id$=\"ask-" + quoted + "\"]');"
                + "if(!card) continue;"
                + "var buttons=card.querySelectorAll('button');"
                + "for(var j=0;j<buttons.length;j++){"
                + "if(buttons[j].textContent==='Yes'){buttons[j].click(); return 'yes';}"
                + "}"
                + "}"
                + "return 'no';"
                + "})()";
    }

    private String eval(String expr, String runId) throws Exception {
        Capture title = run("eval", "--target=browser:auto", "--world=main", "--run-id=" + runId, "--expr=" + expr);
        assertEquals(0, title.code, title.err + title.out);
        return JSON.readTree(title.out).path("value").asText();
    }

    private Capture timed(String... args) {
        long started = System.nanoTime();
        Capture capture = run(args);
        long millis = (System.nanoTime() - started) / 1_000_000L;
        return new Capture(capture.code, capture.out, capture.err, millis);
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
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8), 0);
    }

    private record Capture(int code, String out, String err, long millis) {
    }
}
