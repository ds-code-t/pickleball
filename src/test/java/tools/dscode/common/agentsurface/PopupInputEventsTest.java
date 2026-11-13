package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.AgentControl;
import tools.dscode.common.agentcontrol.PopupSurfaceBridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupInputEventsTest {
    @TempDir
    Path project;

    @Test
    void popupInputIsVisibleToOverlayPoll() throws Exception {
        PopupSurfaceBridge.recordInput(project, "in", Map.of("type", "click", "id", "go", "value", ""));
        AgentControl.Result polled = AgentControl.execute(project, "in", "overlay-poll", Map.of(), java.util.List.of(), "a");
        assertEquals(0, polled.exit());
        String body = String.valueOf(polled.body());
        assertTrue(body.contains("click"), body);
        assertTrue(body.contains("agent_injected"), body);
        String chat = Files.readString(project.resolve(".pickleball/runs/in/agent/chat/overlay.jsonl"));
        assertTrue(chat.contains("overlay input click"), chat);
    }
}
