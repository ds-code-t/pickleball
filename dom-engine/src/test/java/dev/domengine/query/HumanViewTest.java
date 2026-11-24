package dev.domengine.query;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Human and full-access masks. Reachable is computed from stored geometry and is not probed. */
class HumanViewTest {
    private static final String HTML = """
            <html data-viewport-w="100" data-viewport-h="100" data-content-w="100" data-content-h="400">
              <body>
                <button id="seen" data-x="10" data-y="10" data-w="20" data-h="20">Seen</button>
                <button id="gone" hidden data-x="10" data-y="10" data-w="20" data-h="20">Gone</button>
                <button id="below" data-x="10" data-y="200" data-w="20" data-h="20">Below</button>
                <div id="cover" data-x="40" data-y="10" data-w="30" data-h="30" data-paint-order="2" data-opaque="true"></div>
                <button id="under" data-x="45" data-y="15" data-w="10" data-h="10" data-paint-order="1">Under</button>
                <details id="fold" data-x="10" data-y="40" data-w="20" data-h="10" data-region-x="10" data-region-y="50" data-region-w="20" data-region-h="30">
                  <span id="folded">Fold</span>
                </details>
                <details id="bare" data-x="40" data-y="50" data-w="10" data-h="10">
                  <span id="missing">Missing</span>
                </details>
                <details id="locked" disabled data-x="70" data-y="10" data-w="10" data-h="10" data-region-x="70" data-region-y="20" data-region-w="10" data-region-h="10">
                  <span id="secret">Secret</span>
                </details>
                <div id="menu" role="menu"><span id="item" data-x="10" data-y="10" data-w="8" data-h="8">Item</span></div>
                <div id="other" role="window" data-offscreen="true">
                  <button id="bg" data-x="10" data-y="10" data-w="10" data-h="10">Bg</button>
                </div>
              </body>
            </html>
            """;

    @BeforeEach
    void reset() {
        ViewMask.resetForTests();
    }

    @Test
    void humanKeepsSeenAndReachableNodesWithoutChangingThePage() {
        DomNode root = HtmlTree.parse(HTML);
        assertEquals(List.of("seen"), ids(query(root, "#seen", "human")));
        assertEquals(List.of("below"), ids(query(root, "#below", "human")));
        assertEquals(List.of(), ids(query(root, "#gone", "human")));
        assertEquals(List.of(), ids(query(root, "#under", "human")));
        assertEquals(List.of("folded"), ids(query(root, "#folded", "human")));
        assertEquals(List.of(), ids(query(root, "#missing", "human")));
        assertEquals(List.of(), ids(query(root, "#secret", "human")));
        assertEquals(List.of(), ids(query(root, "#item", "human")));
        assertEquals(List.of(), ids(query(root, "#bg", "human")));
        assertEquals(List.of(), ids(query(root, "/html[1]/body[1]/button[2]", "human", "xpath")));
        assertEquals(200, find(root, "below").y, 0.001);
        assertFalse(find(root, "fold").attributes.containsKey("open"));
    }

    @Test
    void fullAccessAndAnActionLookupKeepEveryNode() {
        DomNode root = HtmlTree.parse(HTML);
        assertEquals(List.of("gone"), ids(query(root, "#gone", "full")));
        assertEquals(List.of("gone"), ids(query(root, "#gone", "full-access")));
        assertEquals(List.of("seen", "gone", "below", "under", "bg"), ids(buttons(root, null)));
        Map<String, String> action = new LinkedHashMap<>();
        action.put("css", "#gone");
        action.put("exposure", "human");
        action.put("mask", "off");
        assertEquals(List.of("gone"), ids(Query.run(root, action)));
    }

    @Test
    void paintOrderIsRequiredBeforeOcclusionAndTheMaskIsCached() {
        String open = """
                <html data-viewport-w="100" data-viewport-h="100">
                  <body>
                    <button id="back" data-x="10" data-y="10" data-w="40" data-h="40">Back</button>
                    <button id="front" data-x="20" data-y="20" data-w="10" data-h="10">Front</button>
                  </body>
                </html>
                """;
        DomNode root = HtmlTree.parse(open);
        assertTrue(ids(query(root, "button", "human")).containsAll(List.of("back", "front")));
        ViewMask.resetForTests();
        DomNode cached = HtmlTree.parse(HTML);
        Map<String, String> once = human("#seen", "v1");
        Query.run(cached, once);
        Query.run(cached, once);
        assertEquals(1, ViewMask.evaluations());
        Map<String, String> again = human("#seen", "v2");
        Query.run(cached, again);
        assertEquals(2, ViewMask.evaluations());
    }

    private static Map<String, String> human(String selector, String version) {
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("css", selector);
        flags.put("exposure", "human");
        flags.put("doc-version", version);
        return flags;
    }

    private static ObjectNode query(DomNode root, String selector, String exposure) {
        return query(root, selector, exposure, "css");
    }

    private static ObjectNode query(DomNode root, String selector, String exposure, String flag) {
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put(flag, selector);
        if (exposure != null) flags.put("exposure", exposure);
        return Query.run(root, flags);
    }

    private static ObjectNode buttons(DomNode root, String exposure) {
        return query(root, "button", exposure);
    }

    private static List<String> ids(ObjectNode json) {
        List<String> ids = new ArrayList<>();
        json.path("nodes").forEach(node -> ids.add(node.path("attributes").path("id").asText()));
        return ids;
    }

    private static DomNode find(DomNode node, String id) {
        if (node == null) return null;
        if (id.equals(node.attributes.get("id"))) return node;
        for (DomNode child : node.children) {
            DomNode found = find(child, id);
            if (found != null) return found;
        }
        return null;
    }
}
