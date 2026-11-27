package dev.domengine.query;

import dev.domengine.cdp.CdpException;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.json.Jsons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetweenRelationTest {
    private static final String BOX = """
            <div id="a" data-x="0" data-y="0" data-w="20" data-h="20"></div>
            <div id="b" data-x="200" data-y="100" data-w="20" data-h="20"></div>
            <div id="inside" data-x="80" data-y="30" data-w="30" data-h="30"></div>
            <div id="outside" data-x="0" data-y="190" data-w="10" data-h="10"></div>
            <div id="hidden" data-x="80" data-y="30" data-w="20" data-h="20"></div>
            """;

    @Test
    void keepsTheCandidateInsideTheAnchorBoxAndDropsTheRest() throws Exception {
        DomNode root = HtmlTree.parse(BOX);
        find(root, "hidden").displayed = false;
        assertEquals(List.of("inside"), ids(query(root, "css=div AND between(css=#a, css=#b)")));
        assertEquals(List.of("inside"), ids(query(root, "css=div AND between(css=#a and css=#b)")));
    }

    /**
     * A comma-separated anchor query keeps its own {@code AND}.
     * HtmlUnit page source starts with an XML declaration. The live step uses this pipeline.
     */
    @Test
    void xmlPageSourceKeepsTheSaveButtonBetweenTheAnchors() throws Exception {
        String html = """
                <?xml version="1.0" encoding="ISO-8859-1"?>
                 <html>
                   <head/>
                   <body>
                     <button id="a" data-x="0" data-y="0" data-w="20" data-h="20">A</button>
                     <button id="b" data-x="200" data-y="100" data-w="20" data-h="20">B</button>
                     <button id="out" data-x="0" data-y="190" data-w="10" data-h="10">Save</button>
                     <button id="in" data-x="80" data-y="30" data-w="30" data-h="30">Save</button>
                   </body>
                 </html>
                """;
        DomNode root = HtmlTree.parse(html);
        assertEquals(
                List.of("in"),
                ids(query(root, "(role=button AND text=\"Save\") AND between(role=button AND text=\"A\", role=button AND text=\"B\")"))
        );
    }

    @Test
    void aMissingSecondAnchorIsAnError() throws Exception {
        DomNode root = HtmlTree.parse(BOX);
        CdpException one = assertThrows(CdpException.class, () -> query(root, "css=div AND between(css=#a)"));
        assertTrue(one.getMessage().toLowerCase().contains("between"), one.getMessage());
        CdpException missing = assertThrows(CdpException.class,
                () -> query(root, "css=div AND between(css=#a, css=#missing)"));
        assertTrue(missing.getMessage().toLowerCase().contains("between"), missing.getMessage());
    }

    private static JsonNode query(DomNode root, String pipeline) throws Exception {
        return Jsons.MAPPER.readTree(Jsons.pretty(Query.run(root, Map.of("pipeline", pipeline))));
    }

    private static List<String> ids(JsonNode json) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : json.path("nodes")) {
            String id = node.path("attributes").path("id").asText("");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    private static DomNode find(DomNode node, String id) {
        if (id.equals(node.attributes.get("id"))) return node;
        for (DomNode child : node.children) {
            DomNode found = find(child, id);
            if (found != null) return found;
        }
        return null;
    }
}
