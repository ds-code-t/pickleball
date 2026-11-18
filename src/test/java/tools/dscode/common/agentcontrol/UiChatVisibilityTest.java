package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentsurface.PopupLedger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code ui} chat must show up on the popup ledger, not only as {@code ok}. */
class UiChatVisibilityTest {
    @TempDir
    Path project;

    @Test
    void uiChatAppearsOnThePopupLedger() throws Exception {
        String previous = System.getProperty("pkb_agent_popup");
        try {
            System.setProperty("pkb_agent_popup", "auto");
            AgentControl.Result applied = AgentControl.execute(
                    project,
                    "ui-chat",
                    "ui-apply",
                    Map.of("ops", "[{\"c\":\"chat\",\"text\":\"Step 2 is the password\"}]"),
                    List.of(),
                    "agent"
            );
            assertEquals(0, applied.exit(), String.valueOf(applied.body()));
            assertEquals(Boolean.TRUE, applied.body().get("ok"));
            List<PopupLedger.Row> rows = PopupLedger.read(project, "ui-chat");
            assertTrue(rows.stream().anyMatch(row ->
                    "chat".equals(row.op()) && row.html().contains("Step 2 is the password")), rows.toString());
        } finally {
            if (previous == null) System.clearProperty("pkb_agent_popup");
            else System.setProperty("pkb_agent_popup", previous);
        }
    }
}
