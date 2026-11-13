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
}
