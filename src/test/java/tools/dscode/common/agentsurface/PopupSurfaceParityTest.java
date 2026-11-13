package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.PopupSurfaceBridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupSurfaceParityTest {
    @TempDir
    Path project;

    @AfterEach
    void clear() {
        System.clearProperty("pkb_agent_popup");
    }

    @Test
    void ledgerHtmlMatchesThePageNeutralizer() throws Exception {
        System.setProperty("pkb_agent_popup", "auto");
        String[] samples = {
                "<p onclick=\"alert(1)\">ok</p><script>alert(1)</script>",
                "<a href=\"javascript:alert(1)\">x</a>",
                "<svg onload=\"alert(1)\"><rect width=\"10\" height=\"10\"/></svg>",
                "<svg><foreignObject><script>bad</script></foreignObject></svg>",
                "<iframe srcdoc=\"<script>bad</script>\"></iframe>",
                "<img src=\"data:text/html,<script>bad</script>\">",
                "<div>plain</div>"
        };
        for (String sample : samples) {
            String expected = PopupSurfaceBridge.label(PopupSurfaceBridge.neutralize(sample), "html");
            AgentSurfaceRouter.route(project, "parity", "html-write", Map.of("text", sample), "a");
            String ledger = Files.readString(PopupLedger.file(project, "parity"));
            assertTrue(ledger.contains(escape(expected)), sample + " -> " + expected);
        }
    }

    private static String escape(String html) {
        return html.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
