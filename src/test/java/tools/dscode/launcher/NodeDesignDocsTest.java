package tools.dscode.launcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workbench guide and the {@link AgentHop} comment describe the Node renderer
 * as the launched window. Bare {@code ui} opens that renderer.
 */
class NodeDesignDocsTest {
    @Test
    void guideAndHopDescribeTheNodeRenderer() throws Exception {
        String guide = Files.readString(Path.of("docs/pickleball-workbench.md"));
        assertFalse(guide.contains("MCP and Swing are adapters"), "MCP and Swing are adapters");
        assertFalse(guide.contains("off the Swing event thread"), "off the Swing event thread");
        assertFalse(guide.contains("another Swing control"), "another Swing control");
        assertFalse(guide.contains("reimplementing them in Swing"), "reimplementing them in Swing");
        assertFalse(guide.contains("Swing Event Dispatch Thread"), "Swing Event Dispatch Thread");
        assertFalse(guide.contains("Swing and MCP/HTTP adapters share it"), "Swing and MCP/HTTP adapters share it");
        assertFalse(guide.contains("Swing play/edit"), "Swing play/edit");
        assertFalse(guide.contains("unlocks Swing"), "unlocks Swing");
        assertFalse(guide.contains("Swing banner"), "Swing banner");
        assertFalse(guide.contains("Swing asks:"), "Swing asks:");
        assertFalse(guide.contains("Workbench MCP and Swing intentionally"), "Workbench MCP and Swing intentionally");
        assertFalse(guide.contains("Swing/controller behavior"), "Swing/controller behavior");
        assertTrue(guide.contains("The launched window is the Node renderer"), "launched window");
        assertTrue(guide.contains("store glue put"), "store glue put");

        String hop = Files.readString(Path.of("src/main/java/tools/dscode/launcher/AgentHop.java"));
        assertFalse(hop.contains("stays the Swing controller"), "stays the Swing controller");
        assertTrue(hop.contains("Node renderer"), hop);
    }
}
