package tools.dscode.common.agentfast;

import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Snap within/depth/group/priority and the iframe mutation watch expression. No Chrome. */
class SnapScopeTest {
    @Test
    void withinAndDepthKeepTheScopedSubset() {
        DomNode root = node("div", "page", "", 1);
        DomNode box = node("div", "box", "", 2);
        DomNode in = node("button", "in", "button", 3);
        DomNode wrap = node("div", "", "", 4);
        DomNode deep = node("button", "deep", "button", 5);
        DomNode tab = node("div", "tab1", "tab", 6);
        wrap.children.add(deep);
        box.children.add(in);
        box.children.add(wrap);
        box.children.add(tab);
        root.children.add(box);
        root.children.add(node("button", "out", "button", 7));

        List<DomNode> all = TargetVerb.scopedInteractive(root, "box", -1);
        assertEquals(List.of("in", "deep", "tab1"), ids(all));
        List<DomNode> hash = TargetVerb.scopedInteractive(root, "#box", -1);
        assertEquals(List.of("in", "deep", "tab1"), ids(hash));
        List<DomNode> shallow = TargetVerb.scopedInteractive(root, "box", 1);
        assertEquals(List.of("in", "tab1"), ids(shallow));
        List<DomNode> two = TargetVerb.scopedInteractive(root, "box", 2);
        assertEquals(List.of("in", "deep", "tab1"), ids(two));
        assertTrue(TargetVerb.scopedInteractive(root, "missing", -1).isEmpty());
        List<DomNode> byBackend = TargetVerb.scopedInteractive(root, "2", -1);
        assertEquals(List.of("in", "deep", "tab1"), ids(byBackend));
    }

    @Test
    void groupAndPriorityChangeTheLines() {
        List<Map<String, Object>> rows = List.of(row("link", "a", "Docs"), row("button", "button", "Save"));
        String plain = String.join("\n", TargetVerb.snapLines(rows, Map.of()));
        String grouped = String.join("\n", TargetVerb.snapLines(rows, Map.of("group", "type")));
        String ranked = String.join("\n", TargetVerb.snapLines(rows, Map.of("group", "role", "priority", "on")));
        assertTrue(plain.startsWith("# link"), plain);
        assertTrue(grouped.startsWith("# a"), grouped);
        assertFalse(grouped.equals(plain));
        assertTrue(ranked.startsWith("# button"), ranked);
        assertTrue(ranked.contains("priority=0"), ranked);
        assertFalse(ranked.equals(plain));
    }

    @Test
    void mutationWatchReadsIframeDocuments() {
        String expression = TargetVerb.docVersionExpression();
        assertTrue(expression.contains("contentDocument"), expression);
        assertTrue(expression.contains("__pkbDocCount"), expression);
        assertTrue(expression.contains("iframe,frame"), expression);
    }

    private static List<String> ids(List<DomNode> nodes) {
        return nodes.stream().map(node -> node.attributes.get("id")).toList();
    }

    private static DomNode node(String tag, String id, String role, long backend) {
        DomNode node = new DomNode();
        node.localName = tag;
        node.nodeName = tag;
        node.backendNodeId = backend;
        node.role = role;
        node.displayed = true;
        if (!id.isBlank()) node.attributes.put("id", id);
        return node;
    }

    private static Map<String, Object> row(String role, String type, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role", role);
        row.put("type", type);
        row.put("name", name);
        row.put("ref", "ref:1:1");
        row.put("frame", "top");
        return row;
    }
}
