package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XPathIndexTest {
    @Test
    void indexQualifiedPathsResolveToTheSameNode() {
        String html = """
                <html><body>
                  <input id="a">
                  <input id="b">
                  <div id="host"><template shadowrootmode="open"><button id="go">Go</button><button id="stop">Stop</button></template></div>
                  <iframe srcdoc="<html><body><p id='inner'>In</p></body></html>"></iframe>
                </body></html>
                """;
        DomNode root = HtmlTree.parse(html);
        XPathIndex.Result index = XPathIndex.assign(root);
        DomNode second = XPathIndex.resolve(root, "/html[1]/body[1]/input[2]", "");
        assertNotNull(second);
        assertEquals("b", second.attributes.get("id"));
        assertEquals("/html[1]/body[1]/input[2]", second.xpath);

        DomNode stop = XPathIndex.resolve(root, "/button[2]", "/html[1]/body[1]/div[1]");
        assertNotNull(stop);
        assertEquals("stop", stop.attributes.get("id"));
        assertEquals("/html[1]/body[1]/div[1]", stop.shadowPath);

        DomNode iframe = XPathIndex.resolve(root, "/html[1]/body[1]/iframe[1]", "");
        assertNotNull(iframe);
        DomNode inner = XPathIndex.resolve(root, index.framePath(find(root, "inner")), "/html[1]/body[1]/p[1]", "");
        assertNotNull(inner);
        assertEquals("inner", inner.attributes.get("id"));
        assertEquals(iframe.xpath, index.framePath(inner));

        JsonNode hits = Query.run(root, Map.of("xpath", "/html/body/input"));
        boolean found = false;
        for (JsonNode node : hits.path("nodes")) {
            if ("b".equals(node.path("attributes").path("id").asText())) found = true;
        }
        assertTrue(found, hits.toString());
    }

    private static DomNode find(DomNode node, String id) {
        if (node == null) return null;
        if (id.equals(node.attributes.get("id"))) return node;
        for (DomNode child : node.children) {
            DomNode found = find(child, id);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = find(shadow, id);
            if (found != null) return found;
        }
        return null;
    }
}
