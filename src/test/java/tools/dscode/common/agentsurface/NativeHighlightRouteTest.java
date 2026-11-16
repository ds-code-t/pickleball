package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeHighlightRouteTest {
    @TempDir
    Path project;

    @Test
    void queryJsonSuppliesBoundsAndTheCaptionIsLedgered() throws Exception {
        NativeHighlight.queries = query -> "{\"x\":8,\"y\":9,\"width\":30,\"height\":12}";
        try {
            var result = AgentSurfaceRouter.route(project, "nat", "highlight", Map.of(
                    "target", "native", "query", "role=button name=Save", "text", "Save"), "a").orElseThrow();
            assertEquals(8, result.body().get("x"));
            assertEquals(30, result.body().get("width"));
            if (Boolean.FALSE.equals(result.body().get("drawn"))) assertEquals("headless", result.body().get("reason"));
            String ledger = Files.readString(PopupLedger.file(project, "nat"));
            assertTrue(ledger.contains("Save"), ledger);
            assertTrue(ledger.contains("instructions"), ledger);
        } finally {
            NativeHighlight.queries = query -> "{}";
        }
    }

    @Test
    void overlayQueryUsesTheSameBoundsAsHighlight() throws Exception {
        NativeHighlight.queries = query -> "{\"x\":2,\"y\":3,\"width\":44,\"height\":15}";
        try {
            var result = AgentSurfaceRouter.route(project, "nat", "overlay", Map.of(
                    "target", "native", "query", "role=button", "text", "q"), "a").orElseThrow();
            assertEquals(44, result.body().get("width"));
            assertEquals(15, result.body().get("height"));
            assertEquals("resolved", result.body().get("boundsSource"));
            assertEquals("native", result.body().get("target"));
            if (Boolean.FALSE.equals(result.body().get("drawn"))) assertEquals("headless", result.body().get("reason"));
        } finally {
            NativeHighlight.queries = query -> "{}";
        }
    }

    @Test
    void borderQueryUsesTheSameBoundsAsHighlight() throws Exception {
        NativeHighlight.queries = query -> "{\"x\":2,\"y\":3,\"width\":44,\"height\":15}";
        try {
            var result = AgentSurfaceRouter.route(project, "nat", "border", Map.of(
                    "target", "native", "query", "role=button"), "a").orElseThrow();
            assertEquals(44, result.body().get("width"));
            assertEquals(15, result.body().get("height"));
            assertEquals("resolved", result.body().get("boundsSource"));
            assertEquals("native", result.body().get("target"));
            assertEquals("border", result.body().get("kind"));
            if (Boolean.FALSE.equals(result.body().get("drawn"))) assertEquals("headless", result.body().get("reason"));
        } finally {
            NativeHighlight.queries = query -> "{}";
        }
    }

    @Test
    void arrowNativeUsesRememberedEndpoints() throws Exception {
        tools.dscode.common.agentcontrol.AgentControl.rememberBounds("from-h", 0, 0, 10, 10);
        tools.dscode.common.agentcontrol.AgentControl.rememberBounds("to-h", 40, 20, 10, 10);
        try {
            var result = AgentSurfaceRouter.route(project, "nat", "arrow", Map.of(
                    "target", "native", "from", "from-h", "to", "to-h"), "a").orElseThrow();
            assertEquals("native", result.body().get("target"));
            assertEquals("resolved", result.body().get("boundsSource"));
            assertEquals("arrow", result.body().get("kind"));
            assertTrue(((Number) result.body().get("width")).intValue() > 0);
        } finally {
            tools.dscode.common.agentcontrol.AgentControl.clearRememberedGeometry();
            NativeHighlight.queries = query -> "{}";
        }
    }
}
