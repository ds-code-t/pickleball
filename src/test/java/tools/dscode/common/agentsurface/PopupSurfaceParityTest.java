package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.AgentControl;
import tools.dscode.common.agentcontrol.PopupSurfaceBridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupSurfaceParityTest {
    @TempDir
    Path project;

    @AfterEach
    void clear() {
        try {
            System.setProperty("pkb_agent_popup", "auto");
            Optional<AgentControl.Result> closed = AgentSurfaceRouter.route(project, "parity", "popup-close", Map.of(), "a");
            assertTrue(closed.isPresent());
            assertEquals(Boolean.TRUE, closed.get().body().get("exited"));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        } finally {
            System.clearProperty("pkb_agent_popup");
        }
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

    @Test
    void labelLedgerUsesTheLabelSentence() throws Exception {
        System.setProperty("pkb_agent_popup", "auto");
        AgentSurfaceRouter.route(project, "parity", "label", Map.of("text", "Name", "x", "1", "y", "2"), "a");
        String ledger = Files.readString(PopupLedger.file(project, "parity"));
        assertTrue(ledger.contains("agent-injected label"), ledger);
        assertTrue(ledger.contains("Name"), ledger);
        assertFalse(ledger.contains("<script"), ledger);
    }

    private static String escape(String html) {
        return html.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Test
    void panelCssShared() throws Exception {
        String panelCss = tools.dscode.browser.SurfaceAssets.panelCss();
        assertTrue(panelCss.contains(".pkb-panel {") && panelCss.contains(".pkb-composer textarea"), "shared panel stylesheet incomplete");
        String runtime = tools.dscode.browser.SurfaceAssets.script("pkb-runtime");
        assertFalse(runtime.contains("__PKB_PANEL_CSS__"), "runtime placeholder was not replaced");
        assertTrue(runtime.contains(".pkb-panel {") && runtime.contains(".pkb-composer textarea"), "runtime does not embed the panel stylesheet");
        try (PopupBridge bridge = PopupBridge.start(project, "parity-css")) {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String query = java.net.URLEncoder.encode(bridge.token(), java.nio.charset.StandardCharsets.UTF_8);
            java.net.http.HttpResponse<String> shell = client.send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + bridge.port() + "/")).header("X-Pkb-Popup-Token", bridge.token()).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertTrue(shell.body().contains("assets/surface/panel.css?t=" + query), shell.body());
            java.net.http.HttpResponse<String> css = client.send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + bridge.port() + "/assets/surface/panel.css?t=" + query)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, css.statusCode(), css.body());
            assertEquals(panelCss, css.body());
        }
    }
}
