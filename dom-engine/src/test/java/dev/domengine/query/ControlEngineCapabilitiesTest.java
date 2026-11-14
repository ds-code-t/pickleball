package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.action.PageActions;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.json.Jsons;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlEngineCapabilitiesTest {
    @Test
    void capabilitiesMatchTheCode() throws Exception {
        CliResult result = DomEngineMain.dispatch(List.of("capabilities"));
        JsonNode json = Jsons.MAPPER.readTree(result.stdout());
        assertEquals("Control Engine", json.path("display_name").asText());
        assertEquals(List.of("AND", "OR", "NOT"), texts(json.path("syntax").path("operators")));
        assertEquals(List.of("near", "below", "above", "left-of", "right-of", "inside", "after", "before"),
                texts(json.path("syntax").path("relations")));
        assertEquals(List.of("stage", "filter"), texts(json.path("syntax").path("relation_forms")));
        assertTrue(json.path("syntax").path("negative_relations").asBoolean());
        assertEquals("@<w>", json.path("syntax").path("weights").asText());
        List<String> clauses = new ArrayList<>();
        for (String engine : Query.ENGINES) {
            if (!"frame".equals(engine) && !"shadow".equals(engine)) clauses.add(engine);
        }
        assertEquals(clauses, texts(json.path("syntax").path("clause_types")));
        assertEquals(PageActions.VERBS, texts(json.path("platforms").path("browser").path("verbs")));
        assertEquals(Query.QUERY_FLAGS, texts(json.path("platforms").path("browser").path("query_flags")));
        assertEquals(DesktopTree.VERBS, texts(json.path("platforms").path("desktop").path("verbs")));
        assertEquals(List.of("uia", "ax", "atspi"), texts(json.path("platforms").path("desktop").path("backends")));
        assertFalse(json.path("platforms").path("desktop").path("unsupported").isEmpty());
        for (JsonNode gap : json.path("platforms").path("desktop").path("unsupported")) {
            assertFalse(gap.path("verb").asText().isBlank());
            assertFalse(gap.path("reason").asText().isBlank(), gap.toString());
            assertFalse(gap.path("backends").isEmpty());
        }
        String help = DomEngineMain.dispatch(List.of("help")).stdout();
        assertTrue(help.startsWith("domengine 3.0.0 — Control Engine"), help.lines().findFirst().orElse(""));
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
