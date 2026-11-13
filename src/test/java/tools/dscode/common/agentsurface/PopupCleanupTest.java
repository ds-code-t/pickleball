package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.AgentSurfaceEnd;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupCleanupTest {
    @TempDir
    Path project;

    @Test
    void scenarioEndClearsPanesAndRunEndCloses() throws Exception {
        AgentSurfaceRouter.route(project, "end", "chat-send", Map.of("text", "keep me", "channel", "run"), "a");
        AgentSurfaceRouter.route(project, "end", "html-write", Map.of("text", "<p>gone</p>", "pane", "instructions"), "a");
        AgentSurfaceEnd.complete();
        String ledger = Files.readString(PopupLedger.file(project, "end"));
        assertTrue(ledger.contains("\"op\":\"cleanup\""), ledger);
        assertTrue(ledger.contains("keep me"), ledger);
        assertFalse(ledger.contains("\"op\":\"close\""), ledger);
        AgentSurfaceRouter.onShutdown();
        String closed = Files.readString(PopupLedger.file(project, "end"));
        assertTrue(closed.contains("\"op\":\"close\""), closed);
    }
}
