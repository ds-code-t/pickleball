package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import dev.domengine.snapshot.CompositeSnapshots;
import dev.domengine.snapshot.SnapshotOptions;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The forwarder logs a v2 identity chain and leaves a chain-less node alone. Nothing else calls it. */
class IdentityReplayAdapterTest {
    @TempDir
    Path temp;

    @AfterEach
    void clean() {
        System.clearProperty(CompositeLog.KEY);
        System.clearProperty(CompositeLog.DIR_KEY);
        CompositeLog.resetForTests();
        DomCompositeAdapter.get().resetCommandsForTests();
    }

    @Test
    void replayLogsTheMatchedKindAndSkipsWhenTheChainIsAbsent() throws Exception {
        System.setProperty(CompositeLog.KEY, "identity");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        DomNode recorded = HtmlTree.parse("""
                <html data-w="200" data-h="100"><body>
                  <button id="save" data-x="10" data-y="10" data-w="40" data-h="16">Save</button>
                </body></html>
                """);
        JsonNode nodes = CompositeSnapshots.offline(recorded, SnapshotOptions.fromFlags(Map.of())).path("nodes");
        JsonNode save = null;
        for (JsonNode node : nodes) {
            if ("save".equals(node.path("attributes").path("id").asText())) save = node;
        }
        assertTrue(save != null && save.path("identity").path("chain").isArray(), String.valueOf(nodes));
        DomNode live = HtmlTree.parse("""
                <html data-w="200" data-h="100"><body>
                  <button data-x="10" data-y="10" data-w="40" data-h="16">Save</button>
                </body></html>
                """);
        DomNode button = button(live);
        ObjectNode replay = DomCompositeAdapter.get().replayIdentity(live, save);
        assertEquals("fingerprint", replay.path("matched").asText(), replay.toString());
        assertEquals(button.id(), replay.path("handle").asText(), replay.toString());
        assertEquals(button.backendNodeId, replay.path("backend_node_id").asLong());
        assertEquals(List.of(), DomCompositeAdapter.get().commandsForTests());
        String trace = Files.readString(CompositeLog.file());
        assertTrue(trace.contains("INFO identity replay matched=fingerprint"), trace);

        ObjectNode absent = DomCompositeAdapter.get().replayIdentity(live, Jsons.object());
        assertEquals("none", absent.path("matched").asText());
        assertTrue(absent.path("handle").isNull());
        assertFalse(absent.has("backend_node_id") && !absent.path("backend_node_id").isNull());
        String after = Files.readString(CompositeLog.file());
        assertEquals(trace, after);
        assertTrue(DomCompositeAdapter.get().commandsForTests().isEmpty());
    }

    private static DomNode button(DomNode node) {
        if (node != null && node.nodeType == 1 && "button".equals(node.localName)) {
            boolean child = false;
            for (DomNode one : node.children) if (one.nodeType == 1) child = true;
            if (!child) return node;
        }
        if (node == null) return null;
        for (DomNode child : node.children) {
            DomNode found = button(child);
            if (found != null) return found;
        }
        return null;
    }
}
