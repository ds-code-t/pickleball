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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * D8: the scripted two-actor recipe. One start session; the agent runs control verbs under run id {@code agent},
 * the helper runs human-tier verbs only under a human-locked run id {@code helper}. Choose card, chat hand-off,
 * and timeout/retry run end to end, and both sides record wall-clock timings.
 */
class TwoActorRecipeLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<String> timings = new ArrayList<>();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
        System.out.println("two-actor timings: " + String.join(", ", timings));
    }

    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void helperAndAgentRunTheCoreScenariosOnOneSession() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("recipe.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Recipe</title></head><body>
                <label>Email <input id="email" aria-label="Email"></label>
                <button id="go" onclick="document.title='Went'">Go</button>
                </body></html>""");
        // Start order: the agent starts and waits for the session before the helper attaches (no start race).
        Capture started = agent("start", "--url=" + page.toUri(), "--browser=chrome-headless");
        assertEquals(0, started.code, started.err + started.out);
        assertEquals(0, agent("doctor").code);
        assertEquals(0, helper("session-mode", "--human=on").code);
        Capture refused = helper("eval", "--target=browser:auto", "--expr=1");
        assertEquals(2, refused.code, "the helper is human-tier only: " + refused.out + refused.err);
        assertTrue(refused.err.contains("control-tier verb"), refused.err);
        Capture helperSnap = helper("snap", "--target=browser:auto");
        assertEquals(0, helperSnap.code, helperSnap.err);
        assertTrue(helperSnap.out.contains("Go"), "the helper sees the agent's page: " + helperSnap.out);

        // 1. Choose card: the agent asks without blocking its lane; the helper clicks; the agent collects the answer.
        JsonNode ask = JSON.readTree(agent("ask-and-wait", "--say=Pick one", "--choices=Alpha,Beta", "--async").out);
        String id = ask.path("id").asText();
        assertFalse(id.isBlank(), ask.toString());
        assertEquals(0, helper("act", "--target=browser:auto", "--kind=click", "--query=Alpha").code);
        JsonNode chosen = JSON.readTree(agent("answer-wait", "--id=" + id, "--timeout=10s").out);
        assertEquals("Alpha", chosen.path("value").asText(), chosen.toString());

        // 2. Chat hand-off: the agent's line arrives first; the helper answers in the panel composer.
        assertEquals(0, agent("panel-open", "--tab=chat").code);
        CompletableFuture<Capture> turn = CompletableFuture.supplyAsync(() -> agent("chat-turn", "--text=Type your email, then say done", "--timeout=30s"));
        // The helper reads the agent's line the way a person does: on the page (snap lists chat message rows).
        String seen = "";
        for (int i = 0; i < 40 && !seen.contains("Type your email"); i++) {
            seen = helper("snap", "--target=browser:auto").out;
            if (!seen.contains("Type your email")) Thread.sleep(250);
        }
        assertTrue(seen.contains("Type your email"), seen);
        assertEquals(0, helper("act", "--target=browser:auto", "--kind=type", "--query=Email", "--value=ann@example.com").code);
        assertEquals(0, helper("act", "--target=browser:auto", "--kind=type", "--purpose=message", "--query=Message", "--value=done").code);
        Capture sent = helper("act", "--target=browser:auto", "--kind=click", "--purpose=send", "--query=Send");
        assertEquals(0, sent.code, sent.err + sent.out);
        Capture replied = turn.get(40, TimeUnit.SECONDS);
        System.out.println("chat-turn reply: " + replied.out);
        assertEquals(0, replied.code, replied.err + replied.out);
        assertTrue(replied.out.contains("done"), replied.out);
        // Retest R2: the helper's own run id reads the same panel conversation and its chat cursor moves.
        JsonNode read = JSON.readTree(helper("chat-read", "--last=5").out);
        assertTrue(read.path("items").toString().contains("Type your email"), read.toString());
        assertTrue(read.path("items").toString().contains("\"done\""), read.toString());
        assertFalse(read.path("cursors").path("chat").asText().isBlank(), read.toString());
        JsonNode email = JSON.readTree(agent("eval", "--target=browser:auto", "--world=main", "--expr=document.getElementById('email').value").out);
        assertEquals("ann@example.com", email.path("value").asText(), email.toString());

        // 3. Timeout and retry: a short wait times out cleanly and a retry under the same id gets the answer.
        String retry = JSON.readTree(agent("ask-and-wait", "--say=Ready?", "--choices=Yes,No", "--async").out).path("id").asText();
        Capture timedOut = agent("answer-wait", "--id=" + retry, "--timeout=300");
        assertEquals(1, timedOut.code, timedOut.out + timedOut.err);
        assertTrue(timedOut.out.contains("timeout"), timedOut.out);
        // Retry: the agent asks again under the same id (a timed-out card may already be gone), then waits again.
        JsonNode again = JSON.readTree(agent("ask-and-wait", "--say=Ready?", "--choices=Yes,No", "--async", "--id=" + retry).out);
        assertEquals(retry, again.path("id").asText(), again.toString());
        Capture yes = helper("act", "--target=browser:auto", "--kind=click", "--query=Yes");
        assertEquals(0, yes.code, "the retried card is up: " + yes.out + yes.err);
        JsonNode retried = JSON.readTree(agent("answer-wait", "--id=" + retry, "--timeout=10s").out);
        assertEquals("Yes", retried.path("value").asText(), retried.toString());
        assertTrue(timings.size() > 10, timings.toString());
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
        long began = System.nanoTime();
        int code = WorkbenchAgentCommands.run(command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        synchronized (timings) {
            timings.add(runId + ":" + args[0] + "=" + (System.nanoTime() - began) / 1_000_000L + "ms");
        }
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
