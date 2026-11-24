package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** xpath= and css= on the canonical tree. The index answers absolute tag[n] paths. */
class CanonicalTreeQueryTest {
    private static final String HTML = """
            <html><body>
              <button id="one">One</button>
              <button id="two">Two</button>
              <ul><li id="row"><a id="inner">x</a></li></ul>
              <button id="lead" class="primary extra">Lead</button>
              <span id="next" data-kind="note item">Next</span>
              <span id="later" data-lang="en-US">Later</span>
            </body></html>
            """;

    @Test
    void indexQualifiedXPathResolvesTheNthElement() {
        ObjectNode hit = Query.run(tree(), Map.of("xpath", "/html[1]/body[1]/button[2]"));
        assertEquals(List.of("two"), ids(hit));
        ObjectNode miss = Query.run(tree(), Map.of("xpath", "/html[1]/body[1]/button[9]"));
        assertTrue(miss.path("ok").asBoolean());
        assertEquals(0, miss.path("nodes").size());
    }

    @Test
    void xpathOneEvaluatesDescendantsIndexesAndBooleanPredicates() {
        DomNode root = tree();
        assertEquals(List.of("inner"), ids(Query.run(root, Map.of("xpath", "//li/a"))));
        assertEquals(List.of("two"), ids(Query.run(root, Map.of("xpath", "(//button)[2]"))));
        assertEquals(List.of("lead"), ids(Query.run(root, Map.of(
                "xpath", "//button[@id='lead' and @class='primary extra']"))));
        CdpException invalid = assertThrows(CdpException.class, () -> Query.run(root, Map.of("xpath", "//*[")));
        assertTrue(invalid.getMessage().contains("invalid"), invalid.getMessage());
    }

    @Test
    void cssCombinatorsAndAttributeOperatorsTheIndexRefuses() {
        DomNode root = tree();
        assertEquals(List.of("next"), ids(Query.run(root, Map.of("css", "button + span"))));
        assertEquals(List.of("later"), ids(Query.run(root, Map.of("css", "#lead ~ #later"))));
        assertEquals(List.of("next"), ids(Query.run(root, Map.of("css", "span[data-kind~=note]"))));
        assertEquals(List.of("lead"), ids(Query.run(root, Map.of("css", "[class~=primary]"))));
        assertEquals(List.of("later"), ids(Query.run(root, Map.of("css", "[data-lang|=en]"))));
        assertEquals(List.of("row"), ids(Query.run(root, Map.of("css", "ul > li"))));
    }

    private static DomNode tree() {
        return HtmlTree.parse(HTML);
    }

    private static List<String> ids(JsonNode json) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : json.path("nodes")) ids.add(node.path("attributes").path("id").asText());
        return ids;
    }
}
