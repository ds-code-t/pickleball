package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.dscode.common.GlobalConstants;
import tools.dscode.common.mappings.QuoteParser;
import tools.dscode.common.treeparsing.DefinitionContext;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import dev.domengine.tree.DomNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookStepTest {
    private static final String HTML = """
            <button id="go" style="color: red">Go</button>
            <button id="stop" style="color: blue">Stop</button>
            <button id="dim" style="color: #f00; opacity: 0.2">Dim</button>
            <button id="hot" style="color: rgb(255, 0, 0); opacity: 0.8">Hot</button>
            """;

    @AfterEach
    void resetLog() {
        System.clearProperty("pkb_composite_log");
        System.clearProperty("pkb_composite_log_dir");
        CompositeLog.resetForTests();
    }

    @Test
    void withLookProducesALookStageAndFindsTheNode() throws Exception {
        ElementMatch color = parse("click the Button with look color equaling red.");
        assertTrue(color.attributes.isEmpty(), color.lookClause);
        assertTrue(color.lookClause.contains("with look color equaling red"), color.lookClause);
        String pipeline = SelectorTranslator.phrase(ElementPhrase.of("Button", null).look(color.lookClause)).pipeline();
        assertEquals("(role=button AND look=color=red)", pipeline);
        assertEquals(List.of("go", "dim", "hot"), ids(pipeline));
        assertFalse(ids(pipeline).contains("stop"));

        ElementMatch opacity = parse("click the Button with look opacity greater than 0.5.");
        String hotter = SelectorTranslator.phrase(ElementPhrase.of("Button", null).look(opacity.lookClause)).pipeline();
        assertEquals("(role=button AND look=opacity>0.5)", hotter);
        assertEquals(List.of("hot"), ids(hotter));

        ElementMatch both = parse("click the Button with look color equaling red and with look opacity greater than 0.5.");
        String joined = SelectorTranslator.lookStage(both.lookClause);
        assertEquals("look=color=red,opacity>0.5", joined);
        assertEquals(List.of("hot"), ids("(role=button AND " + joined + ")"));

        EngineQuery query = EngineQuery.of(pipeline);
        assertEquals(pipeline, query.text());
        assertNull(query.seleniumFallback());
        assertEquals("(role=button AND text=\"Save\")", SelectorTranslator.phrase(ElementPhrase.of("Button", "Save")).pipeline());
    }

    @Test
    void attributeGrammarStaysAndLookLoggingStaysOff() throws Exception {
        MatchNode has = element("click the Button with id attribute.");
        assertTrue(has.getStringFromLocalState("atrPredicate").contains("id"), has.getStringFromLocalState("atrPredicate"));
        assertTrue(has.getStringFromLocalState("atrPredicate").contains("attribute"), has.getStringFromLocalState("atrPredicate"));
        assertFalse(has.localStateBoolean("lookPredicate"));

        MatchNode missing = element("click the Button with id noattribute.");
        assertTrue(missing.getStringFromLocalState("atrPredicate").contains("noattribute"), missing.getStringFromLocalState("atrPredicate"));
        assertFalse(missing.localStateBoolean("lookPredicate"));

        MatchNode kept = element("click the Button with id attribute and with look color equaling red.");
        assertTrue(kept.getStringFromLocalState("atrPredicate").contains("attribute"), kept.getStringFromLocalState("atrPredicate"));
        assertFalse(kept.getStringFromLocalState("atrPredicate").contains("look"), kept.getStringFromLocalState("atrPredicate"));
        assertTrue(kept.getStringFromLocalState("lookPredicate").contains("color equaling red"), kept.getStringFromLocalState("lookPredicate"));
        assertEquals("look=color=red", SelectorTranslator.lookStage(kept.getStringFromLocalState("lookPredicate")));

        Path off = Files.createTempDirectory("look-off");
        System.setProperty("pkb_composite_log_dir", off.toString());
        SelectorTranslator.phrase(ElementPhrase.of("Button", null).look("with look color equaling red"));
        if (Files.isDirectory(off)) {
            try (var files = Files.list(off)) {
                assertEquals(0, files.count());
            }
        }

        Path dir = Files.createTempDirectory("look-on");
        System.setProperty("pkb_composite_log", "query");
        System.setProperty("pkb_composite_log_dir", dir.toString());
        String forwarded = SelectorTranslator.phrase(ElementPhrase.of("Button", null).look("with look color equaling red")).pipeline();
        assertTrue(forwarded.contains("look=color=red"), forwarded);
        String log = Files.readString(Files.list(dir).filter(path -> path.getFileName().toString().endsWith(".log")).findFirst().orElseThrow());
        assertTrue(log.startsWith("20"), log);
        assertTrue(log.contains("query"), log);
        assertTrue(log.contains("forward selector="), log);
        assertTrue(log.contains("look=color=red"), log);
    }

    private static ElementMatch parse(String step) {
        return new ElementMatch(null, element(step));
    }

    private static MatchNode element(String step) {
        QuoteParser quotes = new QuoteParser(step);
        String masked = quotes.restoreFromWithOuterBookend(quotes.masked(), GlobalConstants.BOOK_END);
        MatchNode parsed = DefinitionContext.getNodeDictionary().parse(masked);
        MatchNode phrase = parsed.getChild("phrase");
        assertNotNull(phrase, String.valueOf(parsed));
        return phrase.getOrderedChildren("elementMatch").getFirst();
    }

    private static List<String> ids(String pipeline) throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        JsonNode json = Jsons.MAPPER.readTree(Jsons.pretty(Query.run(root, Map.of("pipeline", pipeline))));
        List<String> ids = new ArrayList<>();
        for (JsonNode node : json.path("nodes")) {
            String id = node.path("attributes").path("id").asText("");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }
}
