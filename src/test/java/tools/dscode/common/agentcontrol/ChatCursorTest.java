package tools.dscode.common.agentcontrol;

import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.launcher.WorkbenchAgentCommands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 3: chat history and poll stay on the cursor armed at session start. */
class ChatCursorTest {
    @TempDir
    Path temp;

    @AfterEach
    void stopHost() {
        WorkbenchAgentCommands.run(
                new String[] {"live-stop", temp.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
    }

    @Test
    void historyAndPollSkipLinesFromBeforeTheCursor() throws Exception {
        ChatLog.send(temp, "run-1", "run", "human", "ada", "old-line");
        ChatCursor.arm(temp, "run-1", "run");
        ChatLog.send(temp, "run-1", "run", "human", "ada", "new-line");
        AgentControl.Result history = AgentControl.execute(temp, "run-1", "chat-history", Map.of(), List.of(), "ada");
        AgentControl.Result poll = AgentControl.execute(temp, "run-1", "chat-poll", Map.of(), List.of(), "ada");
        String historyText = String.valueOf(history.body());
        String pollText = String.valueOf(poll.body());
        assertFalse(historyText.contains("old-line"), historyText);
        assertTrue(historyText.contains("new-line"), historyText);
        assertFalse(pollText.contains("old-line"), pollText);
        assertTrue(pollText.contains("new-line"), pollText);
        AgentControl.Result explicit = AgentControl.execute(
                temp, "run-1", "chat-history", Map.of("after", "0"), List.of(), "ada");
        String explicitText = String.valueOf(explicit.body());
        assertTrue(explicitText.contains("old-line"), explicitText);
        assertTrue(explicitText.contains("new-line"), explicitText);
    }

    @Test
    void missingCursorReturnsTheWholeChannel() throws Exception {
        ChatLog.send(temp, "run-2", "run", "human", "ada", "kept-line");
        AgentControl.Result poll = AgentControl.execute(temp, "run-2", "chat-poll", Map.of(), List.of(), "ada");
        assertTrue(String.valueOf(poll.body()).contains("kept-line"), String.valueOf(poll.body()));
    }

    @Test
    void explicitAfterZeroDoesNotAdvanceTheStoredCursor() throws Exception {
        ChatLog.send(temp, "run-3", "run", "human", "ada", "kept-zero");
        AgentControl.Result zero = AgentControl.execute(
                temp, "run-3", "chat-poll", Map.of("after", "0"), List.of(), "ada");
        assertTrue(String.valueOf(zero.body()).contains("kept-zero"), String.valueOf(zero.body()));
        assertTrue(ChatCursor.after(temp, "run-3", "run") == null, String.valueOf(ChatCursor.after(temp, "run-3", "run")));
        AgentControl.Result again = AgentControl.execute(temp, "run-3", "chat-poll", Map.of(), List.of(), "ada");
        assertTrue(String.valueOf(again.body()).contains("kept-zero"), String.valueOf(again.body()));
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void startThenTheSecondWaitTimesOut() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: no Chrome binary on this machine");
        Capture started = run("start", "--url=about:blank", "--browser=chrome", temp.toString());
        assertEquals(0, started.code, started.out + started.err);
        Capture sent = run("chat-send", "--source=human", "--text=cursor-line", temp.toString());
        assertEquals(0, sent.code, sent.out + sent.err);
        Capture first = run("chat_wait", "--timeout=5", temp.toString());
        assertEquals(0, first.code, first.out + first.err);
        assertTrue(first.out.contains("cursor-line"), first.out);
        Capture second = run("chat_wait", "--timeout=1", temp.toString());
        assertEquals(1, second.code, second.out + second.err);
        assertTrue(second.err.contains("timed out"), second.err);
        java.util.regex.Matcher waited = java.util.regex.Pattern.compile("\"elapsedMs\":(\\d+)").matcher(second.out);
        assertTrue(waited.find(), second.out);
        long elapsedMs = Long.parseLong(waited.group(1));
        assertTrue(elapsedMs >= 800, second.out);
        assertTrue(second.out.contains("\"totalMs\":" + elapsedMs), second.out);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void otherSessionEventDoesNotShortenChatPollWait() {
        tools.dscode.control.protocol.AgentEvents.append(temp, "session_ended", "other-session");
        long started = System.nanoTime();
        Capture poll = run("chat-poll", "--wait=3", "--run-id=this-session", temp.toString());
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(0, poll.code, poll.out + poll.err);
        assertTrue(elapsed >= 2500, "elapsed " + elapsed + "\n" + poll.out + poll.err);
        assertFalse(poll.out.contains("other-session"), poll.out);
    }

    private Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
