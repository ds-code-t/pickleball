package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PKB-HCR 2026-11-28 item 9: ask-answer could not answer a visible page card. No browser. */
class AskAnswerPageCardTest {
    @TempDir
    Path project;

    @AfterEach
    void clear() {
        AgentControl.PAGE_CARDS.clear();
    }

    @Test
    void anotherRunIdAnswersThePageCardTheAskerWaitsOn() {
        AgentControl.PAGE_CARDS.put("q9", new AgentControl.PageCard("asker", null, List.of("Yes", "No")));
        AgentControl.Result answered = AgentControl.execute(project, "helper", "ask-answer",
                Map.of("id", "q9", "value", "Yes"), List.of(), "helper");
        assertEquals(0, answered.exit(), String.valueOf(answered.body()));
        assertEquals("page", answered.body().get("type"));
        Map<String, String> found = AnswerRecovery.find(project, "asker", 0L, "q9", List.of("Yes", "No"));
        assertEquals("Yes", found.get("value"), "answer-wait and ask-and-wait read this row");
        AgentControl.Result waited = AgentControl.execute(project, "asker", "answer-wait",
                Map.of("id", "q9", "timeout", "500", "choices", "Yes,No"), List.of(), "agent-1");
        assertEquals(0, waited.exit(), String.valueOf(waited.body()));
        assertEquals("Yes", waited.body().get("value"));
    }

    @Test
    void anUnlistedOptionIsRefusedAndAnUnknownIdKeepsTheStoredCardError() {
        AgentControl.PAGE_CARDS.put("q8", new AgentControl.PageCard("asker", null, List.of("Yes", "No")));
        AgentControl.Result refused = AgentControl.execute(project, "asker", "ask-answer",
                Map.of("id", "q8", "value", "Maybe"), List.of(), "agent-1");
        assertEquals(2, refused.exit());
        assertTrue(String.valueOf(refused.body().get("error")).contains("option is not listed"), String.valueOf(refused.body()));
        AgentControl.Result unknown = AgentControl.execute(project, "asker", "ask-answer",
                Map.of("id", "nope", "value", "Yes"), List.of(), "agent-1");
        assertEquals(2, unknown.exit());
        assertTrue(String.valueOf(unknown.body().get("error")).contains("no ask card nope"), String.valueOf(unknown.body()));
    }
}
