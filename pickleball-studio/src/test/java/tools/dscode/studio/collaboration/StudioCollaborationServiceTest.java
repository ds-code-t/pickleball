package tools.dscode.studio.collaboration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioCollaborationServiceTest {

    @Test
    void sharesAgentActivityAndEditorPresenceWithinStudioSession() {
        StudioCollaborationService collaboration = new StudioCollaborationService();

        StudioAgentSession agent = collaboration.startAgentSession("phase4-test");
        collaboration.note(agent.id(), "Checking the shared workspace");
        collaboration.editorState("desktop-test", "src/test.feature", true, "abc123");

        assertEquals(1, collaboration.agentSessions(false).size());
        assertTrue(collaboration.editorStates().getFirst().dirty());
        assertTrue(collaboration.activity(0L, 100).activities().stream()
                .anyMatch(activity -> "agent.note".equals(activity.operation())));
        assertTrue(collaboration.activity(0L, 100).activities().stream()
                .anyMatch(activity -> "editor.dirty".equals(activity.operation())));

        StudioAgentSession ended = collaboration.endAgentSession(agent.id());
        assertFalse(ended.active());
        assertTrue(collaboration.agentSessions(false).isEmpty());
    }

    @Test
    void boundsActivityAndReportsCursorGap() {
        StudioCollaborationService collaboration = new StudioCollaborationService();
        for (int index = 0; index < 1_005; index++) {
            collaboration.record(
                    StudioClientKind.DESKTOP,
                    "desktop-test",
                    "test.activity",
                    "",
                    String.valueOf(index)
            );
        }

        StudioActivityPage page = collaboration.activity(1L, 500);
        assertTrue(page.gap());
        assertEquals(6L, page.oldestSequence());
        assertEquals(500, page.activities().size());
        assertEquals(1_005L, page.latestSequence());
    }

}
