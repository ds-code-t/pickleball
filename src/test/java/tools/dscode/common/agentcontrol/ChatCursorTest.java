package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 3: chat history and poll stay on the cursor armed at session start. */
class ChatCursorTest {
    @TempDir
    Path temp;

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
}
