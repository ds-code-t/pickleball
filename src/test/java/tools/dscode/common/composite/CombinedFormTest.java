package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import org.junit.jupiter.api.Test;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.DefinitionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombinedFormTest {
    private static final String FIXTURE = """
            <div id="billing" role="region">
              Billing
              <button id="save">Save</button>
              <a id="log">Log</a>
              <button id="go" style="color: red">Go</button>
              <input id="box" type="text">
              <textarea id="area">Notes</textarea>
              <select id="drop"><option>A</option></select>
              <input id="rad" type="radio">
              <input id="chk" type="checkbox" checked>
              <input id="sub" type="submit" value="Send">
            </div>
            <button id="other">Other</button>
            <div id="card" class="Product Card">Mug</div>
            """;

    @Test
    void phrasesEmitOneAndGroupAndFindTheElement() throws Exception {
        expect("(role=button AND text=\"Save\")", "save", ElementPhrase.of("Button", "Save"));
        expect("(role=link AND text~=\"log\")", "log", ElementPhrase.of("Link", "Log").match("containing").quote("single"));
        expect("(role=button AND text^=\"Save\")", "save", ElementPhrase.of("Button", "Save").match("starting"));
        expect("(role=button AND text=\"^Save$\")", "save", ElementPhrase.of("Button", "Save").match("matching"));
        expect("(role=checkbox AND a11y=checked)", "chk", ElementPhrase.of("Checkbox", null).state("checked"));
        expect("(role=button AND look=color=red)", "go", ElementPhrase.of("Button", null).look("with look color equaling red"));
        expect("(role=button AND text=\"Save\" AND css=#save)", "save", ElementPhrase.of("Button", "Save").css("#save"));
        expect("(desc=\"Product Card\" AND text=\"Mug\")", "card", ElementPhrase.of("Product Card", "Mug"));
        expect("(role=region AND text=\"Billing\") >> (role=button AND text=\"Save\")", "save",
                ElementPhrase.of("Button", "Save").context("in", "Billing", "Section"));

        CompositeSelector desktop = SelectorTranslator.phrase(
                ElementPhrase.of("Button", "Save").source("desktop").template("mark.png").ocr("Save"));
        assertEquals(
                "source=desktop >> image=template=mark.png >> image=ocr~=\"Save\" >> (role=button AND text=\"Save\")",
                desktop.pipeline());

        ElementPhrase cssOnly = new ElementPhrase(null, null, null, null, null, null, null, null, null, null, "#save", null, null, null, null);
        CompositeSelector css = SelectorTranslator.phrase(cssOnly);
        assertEquals("#save", css.css());
        assertTrue(css.pipeline() == null, String.valueOf(css.pipeline()));
    }

    @Test
    void builtInCombinedClausesMatchTheFixture() throws Exception {
        ExecutionDictionary dict = DefinitionContext.DEFAULT_EXECUTION_DICTIONARY;
        assertEquals("role=button", dict.combinedFor("Button", null, null).orElseThrow());
        assertEquals("(role=button AND text=\"Save\")",
                dict.combinedFor("Button", ValueWrapper.createValueWrapper("Save"), ExecutionDictionary.Op.EQUALS).orElseThrow());
        assertEquals("(role=button AND text~=\"sav\")",
                dict.combinedFor("Buttons", ValueWrapper.createValueWrapper("sav"), ExecutionDictionary.Op.CONTAINS).orElseThrow());
        assertEquals("(role=link AND text^=\"L\")",
                dict.combinedFor("Link", ValueWrapper.createValueWrapper("L"), ExecutionDictionary.Op.STARTS_WITH).orElseThrow());
        assertEquals("(role=button AND text=\"^Save$\")",
                dict.combinedFor("Button", ValueWrapper.createValueWrapper("Save"), ExecutionDictionary.Op.MATCHES).orElseThrow());
        assertTrue(dict.combinedFor("Button", ValueWrapper.createValueWrapper("Save"), ExecutionDictionary.Op.ENDS_WITH).isEmpty());
        assertTrue(dict.combinedFor("Section", ValueWrapper.createValueWrapper("Billing"), ExecutionDictionary.Op.EQUALS).isEmpty());
        assertTrue(dict.combinedFor("Close Button", null, null).isEmpty());
        assertTrue(dict.combinedFor("Icon", null, null).isEmpty());
        assertTrue(dict.combinedFor("Menu", null, null).isEmpty());
        assertTrue(dict.combinedFor("Text", null, null).isEmpty());
        assertTrue(dict.combinedFor("Toggle", null, null).isEmpty());
        assertTrue(dict.combinedFor("Field", null, null).isEmpty());

        assertEquals(List.of("save"), ids(dict.combinedFor("Button", ValueWrapper.createValueWrapper("Save"), ExecutionDictionary.Op.EQUALS).orElseThrow()));
        assertEquals(List.of("log"), ids(dict.combinedFor("Link", ValueWrapper.createValueWrapper("Log"), ExecutionDictionary.Op.EQUALS).orElseThrow()));
        assertTrue(ids(dict.combinedFor("Textbox", null, null).orElseThrow()).contains("box"));
        assertTrue(ids(dict.combinedFor("Textarea", null, null).orElseThrow()).contains("area"));
        assertEquals(List.of("drop"), ids(dict.combinedFor("Dropdown", null, null).orElseThrow()));
        assertEquals(List.of("rad"), ids(dict.combinedFor("Radio Button", null, null).orElseThrow()));
        assertEquals(List.of("chk"), ids(dict.combinedFor("Checkbox", null, null).orElseThrow()));
        assertTrue(ids(dict.combinedFor("Submit Button", null, null).orElseThrow()).contains("sub"));
    }

    private static void expect(String pipeline, String id, ElementPhrase phrase) throws Exception {
        CompositeSelector selector = SelectorTranslator.phrase(phrase);
        assertEquals(pipeline, selector.pipeline(), selector.describe());
        assertEquals(List.of(id), ids(selector.pipeline()));
    }

    private static List<String> ids(String pipeline) throws Exception {
        JsonNode json = Jsons.MAPPER.readTree(Jsons.pretty(Query.run(HtmlTree.parse(FIXTURE), Map.of("pipeline", pipeline))));
        List<String> ids = new ArrayList<>();
        for (JsonNode node : json.path("nodes")) {
            String id = node.path("attributes").path("id").asText("");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }
}
