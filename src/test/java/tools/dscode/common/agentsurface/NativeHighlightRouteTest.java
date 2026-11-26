package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            assertEquals("sibling", result.body().get("mutate"));
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
    void explicitDomMutateStaysOnTheHighlightRoute() {
        var result = AgentSurfaceRouter.route(project, "nat", "highlight", Map.of(
                "target", "native", "x", "1", "y", "2", "width", "3", "height", "4", "mutate", "dom"), "a").orElseThrow();
        assertEquals("dom", result.body().get("mutate"));
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

    @Test
    void visualRemovalWithoutAnIdHidesTheBoxItsShowDrew() throws Exception {
        String[][] pairs = {
                {"label", "unlabel"}, {"border", "unborder"}, {"step-badge", "unstep-badge"},
                {"magnifier", "unmagnifier"}, {"spotlight", "unspotlight"}
        };
        for (String[] pair : pairs) {
            var shown = AgentSurfaceRouter.route(project, "nat", pair[0], Map.of(
                    "target", "native", "x", "4", "y", "5", "width", "30", "height", "12",
                    "text", "t", "number", "1"), "a").orElseThrow();
            String id = String.valueOf(shown.body().get("id"));
            assertTrue(id.startsWith("native-"), id);
            var hidden = AgentSurfaceRouter.route(project, "nat", pair[1], Map.of("target", "native"), "a").orElseThrow();
            assertEquals(id, hidden.body().get("id"), pair[1] + " hid a different native box");
            assertEquals(true, hidden.body().get("reversed"));
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                assertEquals(true, hidden.body().get("drawn"), pair[1] + " did not find the box " + id);
                assertFalse(tools.dscode.control.protocol.surface.NativeHighlightBox.shown().contains(id), id);
            }
        }
    }

    @Test
    void nativeUnVerbRefusesARectangleDrawnForAnotherKind() throws Exception {
        AgentSurfaceRouter.route(project, "nat", "border", Map.of(
                "target", "native", "id", "kind-a", "x", "1", "y", "1", "width", "20", "height", "10"), "a").orElseThrow();
        IllegalArgumentException wrong = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> AgentSurfaceRouter.route(project, "nat", "unlabel", Map.of("target", "native", "id", "kind-a"), "a"));
        assertTrue(wrong.getMessage().contains("is a border, not a label; use unborder"), wrong.getMessage());
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            assertTrue(tools.dscode.control.protocol.surface.NativeHighlightBox.shown().contains("kind-a"));
        }
        var right = AgentSurfaceRouter.route(project, "nat", "unborder", Map.of("target", "native", "id", "kind-a"), "a").orElseThrow();
        assertEquals(0, right.exit(), String.valueOf(right.body()));
        assertEquals(true, right.body().get("reversed"));
    }

    @Test
    void scenarioEndHidesEveryNativeBox() throws Exception {
        AgentSurfaceRouter.route(project, "nat", "border", Map.of(
                "target", "native", "id", "end-a", "x", "1", "y", "1", "width", "20", "height", "10"), "a").orElseThrow();
        AgentSurfaceRouter.route(project, "nat", "spotlight", Map.of(
                "target", "native", "id", "end-b", "x", "30", "y", "1", "width", "20", "height", "10"), "a").orElseThrow();
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            assertTrue(tools.dscode.control.protocol.surface.NativeHighlightBox.shown().containsAll(java.util.List.of("end-a", "end-b")));
        }
        AgentSurfaceRouter.onScenarioEnd();
        assertTrue(tools.dscode.control.protocol.surface.NativeHighlightBox.shown().isEmpty(),
                String.valueOf(tools.dscode.control.protocol.surface.NativeHighlightBox.shown()));
        var again = AgentSurfaceRouter.route(project, "nat", "unborder", Map.of("target", "native", "id", "end-a"), "a").orElseThrow();
        if (!java.awt.GraphicsEnvironment.isHeadless()) assertEquals("absent", again.body().get("reason"));
    }
}
