package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** PKB-HCR 2026-11-28 item 23: panel-open answers with the tab name it takes. */
class HcrPanelTabTest {
    @Test
    void paneIdsMapToTheNamesPanelOpenTakes() {
        assertEquals("guide", AgentControl.publicTab("steps"));
        assertEquals("recorder", AgentControl.publicTab("record"));
        assertEquals("chat", AgentControl.publicTab("chat"));
        assertEquals("chat", AgentControl.publicTab("null"));
    }
}
