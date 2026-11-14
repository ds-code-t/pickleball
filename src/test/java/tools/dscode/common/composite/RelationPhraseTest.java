package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelationPhraseTest {
    private static final String HTML = """
            <button id="before" data-x="0" data-y="-400" data-w="20" data-h="10">Save</button>
            <div id="billing" role="region" data-x="0" data-y="80" data-w="40" data-h="20">
              Billing
              <button id="nested" data-x="4" data-y="84" data-w="10" data-h="8">Save</button>
            </div>
            <button id="after" data-x="200" data-y="200" data-w="20" data-h="10">Save</button>
            <div id="side" data-x="300" data-y="200" data-w="20" data-h="10">Side</div>
            """;

    @Test
    void contextWordsBecomeRelationFilters() throws Exception {
        expect("(role=button AND text=\"Save\") AND after(role=region AND text=\"Billing\")", "after",
                ElementPhrase.of("Button", "Save").context("after", "Billing", "Section"));
        expect("(role=button AND text=\"Save\") AND before(role=region AND text=\"Billing\")", "before",
                ElementPhrase.of("Button", "Save").context("before", "Billing", "Section"));
        expect("(role=button AND text=\"Save\") AND above(role=region AND text=\"Billing\")", "before",
                ElementPhrase.of("Button", "Save").context("above", "Billing", "Section"));
        expect("(role=button AND text=\"Save\") AND below(role=region AND text=\"Billing\")", "after",
                ElementPhrase.of("Button", "Save").context("below", "Billing", "Section"));
        expect("(role=button AND text=\"Save\") AND left-of(role=region AND text=\"Billing\")", null,
                ElementPhrase.of("Button", "Save").context("left of", "Billing", "Section"));
        expect("(role=button AND text=\"Save\") AND right-of(role=region AND text=\"Billing\")", "after",
                ElementPhrase.of("Button", "Save").context("right of", "Billing", "Section"));
        expect("(role=button AND text=\"Save\") AND near(role=region AND text=\"Billing\")", "nested",
                ElementPhrase.of("Button", "Save").context("near", "Billing", "Section"));
        expect("(role=button AND text=\"Save\") AND inside(role=region AND text=\"Billing\")", "nested",
                ElementPhrase.of("Button", "Save").context("inside", "Billing", "Section"));
        expect("(role=region AND text=\"Billing\") >> (role=button AND text=\"Save\")", "nested",
                ElementPhrase.of("Button", "Save").context("in", "Billing", "Section"));
    }

    private static void expect(String pipeline, String id, ElementPhrase phrase) throws Exception {
        CompositeSelector selector = SelectorTranslator.phrase(phrase);
        assertEquals(pipeline, selector.pipeline(), selector.describe());
        List<String> ids = ids(selector.pipeline());
        if (id == null) assertEquals(List.of(), ids, pipeline);
        else assertEquals(List.of(id), ids, pipeline);
    }

    private static List<String> ids(String pipeline) throws Exception {
        JsonNode json = Jsons.MAPPER.readTree(Jsons.pretty(Query.run(HtmlTree.parse(HTML), Map.of("pipeline", pipeline))));
        List<String> ids = new ArrayList<>();
        for (JsonNode node : json.path("nodes")) {
            String found = node.path("attributes").path("id").asText("");
            if (!found.isEmpty()) ids.add(found);
        }
        return ids;
    }
}
