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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * HCR-12: a person's panel message reached the chat log only when the agent ran {@code chat-poll} (58 s late), and
 * {@code wait-any --chat=human} never fired. The message is now stored as it is sent and wakes the wait.
 */
class HumanChatPushLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void humanPanelMessageIsPushedAndWakesWaitAny() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("chat.html");
        Files.writeString(page, "<!DOCTYPE html><html><head><title>Chat</title></head><body><button>Go</button></body></html>");
        Capture started = agent("start", "--url=" + page.toUri(), "--browser=chrome-headless");
        assertEquals(0, started.code, started.err + started.out);
        assertEquals(0, helper("session-mode", "--human=on").code);
        assertEquals(0, agent("panel-open", "--tab=chat").code);

        CompletableFuture<Capture> waiting = CompletableFuture.supplyAsync(() -> agent("wait-any", "--chat=human", "--timeout=20000"));
        Thread.sleep(1000);
        assertEquals(0, helper("act", "--target=browser:auto", "--kind=type", "--purpose=message", "--query=Message", "--value=ping-from-person").code);
        long sentAt = System.nanoTime();
        Capture sent = helper("act", "--target=browser:auto", "--kind=click", "--purpose=send", "--query=Send");
        assertEquals(0, sent.code, sent.err + sent.out);

        Capture fired = waiting.get(30, TimeUnit.SECONDS);
        long lagMs = (System.nanoTime() - sentAt) / 1_000_000L;
        assertEquals(0, fired.code, "wait-any --chat=human never fired: " + fired.out + fired.err);
        assertTrue(fired.out.contains("ping-from-person"), fired.out);
        assertTrue(fired.out.contains("\"source\":\"human\""), fired.out);
        assertTrue(lagMs < 10_000, "fired " + lagMs + " ms after the send");

        // Stored once in the run chat without any chat-poll by the agent.
        long copies = 0;
        try (Stream<Path> files = Files.walk(temp.resolve(".pickleball"))) {
            for (Path file : files.filter(f -> f.getFileName().toString().equals("run.jsonl")).toList()) {
                copies += Files.readString(file).split("ping-from-person", -1).length - 1;
            }
        }
        assertEquals(1, copies, "the panel line is stored once");
        JsonNode history = JSON.readTree(agent("chat-history", "--after=0").out);
        assertTrue(history.toString().contains("ping-from-person"), history.toString());
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
