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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Two-actor collaboration fixes (docs/roadmaps/3.0.0-collab-fixes-roadmap.md) on a live start host.
 * Each test starts its own headless Chrome through the production {@code start} verb.
 */
class CollabFixesLiveTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    Path page(String name, String body) throws Exception {
        Path page = temp.resolve(name);
        Files.writeString(page, "<!DOCTYPE html><html><head><title>Collab</title></head><body>" + body + "</body></html>");
        return page;
    }

    void start(Path page, String runId) {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=" + runId);
        assertEquals(0, started.code, started.err + started.out);
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void c2_cardClickIsAppliedANoOpIsNotAndTabMovesFocus() throws Exception {
        start(page("c2.html", """
                <input id="first" aria-label="First"><input id="second" aria-label="Second">
                <button id="noop">Nothing</button>
                <input id="email" name="email" aria-label="Email">
                """), "c2");
        Capture asked = run("ask", "--say=Pick", "--choices=Alpha,Beta", "--run-id=c2");
        assertEquals(0, asked.code, asked.err + asked.out);
        Capture card = run("act", "--target=browser:auto", "--query=Alpha", "--kind=click", "--pick=1", "--run-id=c2");
        JsonNode cardBody = JSON.readTree(card.out);
        assertEquals("true", cardBody.path("applied").asText(), card.out);

        Capture noop = run("act", "--target=browser:auto", "--query=Nothing", "--kind=click", "--run-id=c2");
        JsonNode noopBody = JSON.readTree(noop.out);
        assertNotEquals("true", noopBody.path("applied").asText(), "a click with no effect is never applied: " + noop.out);

        Capture tab = run("act", "--target=browser:auto", "--query=First", "--kind=press", "--value=Tab", "--run-id=c2");
        assertEquals(0, tab.code, tab.err + tab.out);
        assertEquals("true", JSON.readTree(tab.out).path("applied").asText(), tab.out);
        assertEquals("second", eval("document.activeElement && document.activeElement.id", "c2"));

        Capture fill = run("form-fill", "--target=browser:auto", "--fields=email=abc@example.com", "--run-id=c2");
        assertEquals(0, fill.code, fill.err + fill.out);
        Thread.sleep(800);
        List<String> rows = interactionRows("email");
        assertFalse(rows.isEmpty(), "form-fill left no interaction row");
        for (String row : rows) assertFalse(row.contains("\"source\":\"human\""), "agent form-fill tagged human: " + row);
    }

    List<String> interactionRows(String id) throws Exception {
        Path events = temp.resolve(".pickleball").resolve("agent-events.jsonl");
        if (!Files.exists(events)) return List.of();
        return Files.readAllLines(events).stream()
                .filter(line -> line.contains("\"interaction\"") && line.contains("\"id\":\"" + id + "\""))
                .toList();
    }

    String eval(String expr, String runId) throws Exception {
        Capture value = run("eval", "--target=browser:auto", "--world=main", "--run-id=" + runId, "--expr=" + expr);
        assertEquals(0, value.code, value.err + value.out);
        return JSON.readTree(value.out).path("value").asText();
    }

    Capture run(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        long started = System.nanoTime();
        int code = WorkbenchAgentCommands.run(command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8),
                (System.nanoTime() - started) / 1_000_000L);
    }

    record Capture(int code, String out, String err, long millis) {
    }
}
