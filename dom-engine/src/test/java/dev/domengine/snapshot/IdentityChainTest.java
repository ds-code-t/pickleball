package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.query.HtmlTree;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replay tries the recorded chain and reports the step that matched. */
class IdentityChainTest {
    @Test
    void stableAttributesWinWhenTheyAreUnique() {
        DomNode root = tree("""
                <html data-w="200" data-h="100"><body>
                  <button id="save">Save</button>
                  <button data-testid="next">Next</button>
                  <button data-uia-automation-id="9no">Nine</button>
                  <button id="dup" data-testid="one">A</button>
                  <button id="dup" data-testid="two">B</button>
                </body></html>
                """);
        JsonNode nodes = snapshot(root).path("nodes");
        assertEquals("id=save", step(nodes, "save", 0));
        assertEquals("data-testid=next", step(nodes, "next", 0));
        assertEquals("data-uia-automation-id=9no", step(nodes, "9no", 0));
        assertEquals("data-testid=one", step(nodes, "one", 0));
        ObjectNode replay = IdentityChain.replay(tree("""
                <html data-w="200" data-h="100"><body><button id="save">Save</button></body></html>
                """), chain(nodes, "save"));
        assertEquals("stable", replay.path("matched").asText());
        assertEquals("id=save", replay.path("locator").asText());
        assertEquals(0, replay.path("index").asInt());
    }

    @Test
    void replayFallsThroughNativeFingerprintAndXPath() {
        DomNode recorded = tree("""
                <html data-w="200" data-h="100"><body>
                  <button id="same">Go</button>
                  <button id="same" uia-runtime-id="r.1" data-x="10" data-y="10" data-w="20" data-h="8">Go</button>
                  <button id="same" data-x="40" data-y="10" data-w="20" data-h="8">Stop</button>
                  <button id="numeric" uia-runtime-id="9">Num</button>
                </body></html>
                """);
        paint(recorded, "r.1", 0xFFL);
        paint(recorded, "Stop", 0xFFL);
        JsonNode nodes = snapshot(recorded).path("nodes");
        JsonNode nativeChain = chain(nodes, "r.1");
        assertEquals("native-path", nativeChain.get(0).path("kind").asText());
        assertEquals("uia-runtime-id=r.1", nativeChain.get(0).path("locator").asText());
        assertFalse(chain(nodes, "9").toString().contains("native-path"), chain(nodes, "9").toString());

        DomNode same = tree("""
                <html data-w="200" data-h="100"><body>
                  <button id="same">Go</button>
                  <button id="same" uia-runtime-id="r.1" data-x="10" data-y="10" data-w="20" data-h="8">Go</button>
                  <button id="same" data-x="40" data-y="10" data-w="20" data-h="8">Stop</button>
                </body></html>
                """);
        ObjectNode nativeHit = IdentityChain.replay(same, nativeChain);
        assertEquals("native-path", nativeHit.path("matched").asText());
        assertEquals(0, nativeHit.path("index").asInt());

        JsonNode fingerChain = chain(nodes, "Stop");
        assertEquals("fingerprint", fingerChain.get(0).path("kind").asText(), fingerChain.toString());
        DomNode moved = tree("""
                <html data-w="200" data-h="100"><body>
                  <button id="same">Go</button>
                  <button id="same" data-x="10" data-y="10" data-w="20" data-h="8">Go</button>
                  <button id="same" data-x="44" data-y="12" data-w="20" data-h="8">Stop</button>
                </body></html>
                """);
        paint(moved, "Stop", 0xF7L);
        ObjectNode fingerHit = IdentityChain.replay(moved, fingerChain);
        assertEquals("fingerprint", fingerHit.path("matched").asText(), fingerHit.toString());

        DomNode renamed = tree("""
                <html data-w="200" data-h="100"><body>
                  <button id="same">Go</button>
                  <button id="same" data-x="10" data-y="10" data-w="20" data-h="8">Go</button>
                  <button id="same" data-x="40" data-y="10" data-w="20" data-h="8">Other</button>
                </body></html>
                """);
        ObjectNode xpathHit = IdentityChain.replay(renamed, fingerChain);
        assertEquals("xpath", xpathHit.path("matched").asText(), xpathHit.toString());
        assertTrue(xpathHit.path("index").asInt() > 0);

        ObjectNode none = IdentityChain.replay(tree("<html><body></body></html>"), fingerChain);
        assertEquals("none", none.path("matched").asText());
        assertEquals(-1, none.path("index").asInt());
    }

    private static JsonNode snapshot(DomNode root) {
        return CompositeSnapshots.offline(root, SnapshotOptions.fromFlags(Map.of()));
    }

    private static DomNode tree(String html) {
        return HtmlTree.parse(html);
    }

    private static JsonNode chain(JsonNode nodes, String marker) {
        for (JsonNode node : nodes) {
            String id = node.path("attributes").path("id").asText("");
            String testid = node.path("attributes").path("data-testid").asText("");
            String auto = node.path("attributes").path("data-uia-automation-id").asText("");
            String runtime = node.path("attributes").path("uia-runtime-id").asText("");
            String text = node.path("text").asText("");
            if (marker.equals(id) || marker.equals(testid) || marker.equals(auto) || marker.equals(runtime) || marker.equals(text)) {
                return node.path("identity").path("chain");
            }
        }
        throw new AssertionError("missing " + marker + " in " + nodes);
    }

    private static String step(JsonNode nodes, String marker, int index) {
        return chain(nodes, marker).get(index).path("locator").asText();
    }

    private static void paint(DomNode node, String marker, long dhash) {
        if (node == null) return;
        String runtime = node.attributes.get("uia-runtime-id");
        String text = node.nodeValue;
        boolean hit = marker.equals(node.attributes.get("id"))
                || marker.equals(runtime)
                || marker.equals(dev.domengine.query.HtmlTree.textOf(node).trim());
        if (hit && node.nodeType == 1) {
            DomNode.Paint paint = new DomNode.Paint();
            paint.hasDhash = true;
            paint.dhash = dhash;
            node.paint = paint;
        }
        for (DomNode child : node.children) paint(child, marker, dhash);
    }
}
