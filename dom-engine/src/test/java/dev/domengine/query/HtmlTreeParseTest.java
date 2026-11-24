package dev.domengine.query;

import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Void starts stay linear even when an explicit end tag is present, and that end tag still makes a container. */
class HtmlTreeParseTest {
    @Test
    void fiveThousandInputsParseInLinearTime() {
        StringBuilder html = new StringBuilder("<html><body>");
        for (int i = 0; i < 5000; i++) html.append("<input id=\"i").append(i).append("\">");
        html.append("</body></html>");
        DomNode root = assertTimeoutPreemptively(ofSeconds(2), () -> HtmlTree.parse(html.toString()));
        assertEquals(5000, count(root, "input"));
        DomNode body = element(root, "body");
        assertEquals(5000, body.children.size());
    }

    @Test
    void fiveThousandBareInputsPlusOneContainerStayLinear() {
        StringBuilder html = new StringBuilder("<html><body>");
        for (int i = 0; i < 5000; i++) html.append("<input id=\"i").append(i).append("\">");
        html.append("<input id=\"box\"><span id=\"child\">c</span></input>");
        html.append("<input id=\"after\">");
        html.append("</body></html>");
        DomNode root = assertTimeoutPreemptively(ofSeconds(2), () -> HtmlTree.parse(html.toString()));
        assertEquals(5002, count(root, "input"));
        DomNode body = element(root, "body");
        assertEquals(5002, body.children.size());
        DomNode box = byId(root, "box");
        assertEquals("input", byId(root, "child").parent.localName);
        assertEquals(box, byId(root, "child").parent);
        assertEquals(1, box.children.size());
        assertEquals("body", byId(root, "after").parent.localName);
        assertEquals("body", byId(root, "i0").parent.localName);
        assertEquals("body", byId(root, "i4999").parent.localName);
    }

    @Test
    void explicitEndContainsChildrenAndABareVoidDoesNotSwallowSiblings() {
        DomNode root = HtmlTree.parse("""
                <html><body>
                <br>
                <input id="closed"><span id="child">c</span></input>
                <img id="open">
                <button id="after">A</button>
                <input id="nested"><b id="inner">x</b><i>y</i></input>
                </body></html>
                """);
        assertEquals("body", element(root, "br").parent.localName);
        DomNode closed = byId(root, "closed");
        assertEquals("input", byId(root, "child").parent.localName);
        assertEquals(closed, byId(root, "child").parent);
        assertEquals("body", byId(root, "open").parent.localName);
        assertEquals("body", byId(root, "after").parent.localName);
        assertEquals("input", byId(root, "inner").parent.localName);
        assertFalse("input".equals(byId(root, "after").parent.localName));
        assertTrue(count(root, "img") == 1);
    }

    private static int count(DomNode node, String tag) {
        if (node == null) return 0;
        int total = tag.equals(node.localName) ? 1 : 0;
        for (DomNode child : node.children) total += count(child, tag);
        return total;
    }

    private static DomNode element(DomNode node, String tag) {
        if (node != null && tag.equals(node.localName)) return node;
        if (node == null) return null;
        for (DomNode child : node.children) {
            DomNode found = element(child, tag);
            if (found != null) return found;
        }
        return null;
    }

    private static DomNode byId(DomNode node, String id) {
        if (node != null && node.nodeType == 1 && id.equals(node.attributes.get("id"))) return node;
        if (node == null) return null;
        for (DomNode child : node.children) {
            DomNode found = byId(child, id);
            if (found != null) return found;
        }
        return null;
    }
}
