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
        DomNode live = tree("""
                <html data-w="200" data-h="100"><body><button id="save">Save</button></body></html>
                """);
        ObjectNode replay = IdentityChain.replay(live, chain(nodes, "save"));
        assertEquals("stable", replay.path("matched").asText());
        assertEquals("id=save", replay.path("locator").asText());
        assertEquals(0, replay.path("index").asInt());
        assertHandle(replay, live, "save");
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
        assertHandle(nativeHit, same, "r.1");

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
        assertHandle(fingerHit, moved, "Stop");

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
        assertHandle(xpathHit, renamed, "Other");

        ObjectNode none = IdentityChain.replay(tree("<html><body></body></html>"), fingerChain);
        assertEquals("none", none.path("matched").asText());
        assertEquals(-1, none.path("index").asInt());
        assertTrue(none.path("handle").isNull());
        assertTrue(none.path("backend_node_id").isMissingNode() || none.path("backend_node_id").isNull());
    }

    @Test
    void chainsForLiveRefsMatchTheSnapshotChainAndXPathComesLast() {
        DomNode root = tree("""
                <html data-w="200" data-h="100"><body>
                  <button id="save">Save</button>
                  <button>Go</button>
                  <button>Go</button>
                </body></html>
                """);
        java.util.List<DomNode> buttons = new java.util.ArrayList<>();
        collect(root, "button", buttons);
        Map<DomNode, JsonNode> chains = IdentityChain.chains(root, buttons);
        assertEquals(3, chains.size());
        JsonNode nodes = snapshot(root).path("nodes");
        assertEquals(chain(nodes, "save"), chains.get(buttons.get(0)), "the live-ref chain is the snapshot chain");
        JsonNode second = chains.get(buttons.get(2));
        assertEquals("xpath", second.get(second.size() - 1).path("kind").asText(), second.toString());
        for (int i = 0; i < second.size() - 1; i++) assertFalse("xpath".equals(second.get(i).path("kind").asText()), second.toString());
        DomNode reordered = tree("""
                <html data-w="200" data-h="100"><body>
                  <button>Go</button>
                  <button id="save">Save</button>
                </body></html>
                """);
        ObjectNode replay = IdentityChain.replay(reordered, chains.get(buttons.get(0)));
        assertEquals("stable", replay.path("matched").asText(), "a stable id wins over the positional XPath");
        assertHandle(replay, reordered, "save");
        assertTrue(IdentityChain.chains(null, buttons).isEmpty());
    }

    private static void collect(DomNode node, String tag, java.util.List<DomNode> into) {
        if (node == null) return;
        if (tag.equals(node.tag())) into.add(node);
        for (DomNode child : node.children) collect(child, tag, into);
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

    /** The handle is the same id written on nodes[].handle. backend_node_id is present only when positive. */
    private static void assertHandle(ObjectNode replay, DomNode root, String marker) {
        DomNode node = element(root, marker);
        assertEquals(node.id(), replay.path("handle").asText(), replay.toString());
        if (node.backendNodeId > 0) assertEquals(node.backendNodeId, replay.path("backend_node_id").asLong());
        else assertTrue(replay.path("backend_node_id").isMissingNode() || replay.path("backend_node_id").isNull());
    }

    private static DomNode element(DomNode node, String marker) {
        if (node != null && node.nodeType == 1) {
            String runtime = node.attributes.get("uia-runtime-id");
            if (marker.equals(node.attributes.get("id"))
                    || marker.equals(node.attributes.get("data-testid"))
                    || marker.equals(node.attributes.get("data-uia-automation-id"))
                    || marker.equals(runtime)
                    || marker.equals(HtmlTree.textOf(node).trim())) {
                boolean own = marker.equals(node.attributes.get("id"))
                        || marker.equals(node.attributes.get("data-testid"))
                        || marker.equals(node.attributes.get("data-uia-automation-id"))
                        || marker.equals(runtime);
                if (own || !hasElementChild(node)) return node;
            }
        }
        if (node == null) return null;
        for (DomNode child : node.children) {
            DomNode found = element(child, marker);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean hasElementChild(DomNode node) {
        for (DomNode child : node.children) if (child.nodeType == 1) return true;
        return false;
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
