package tools.dscode.engine;

import io.cucumber.java.en.Given;
import org.junit.jupiter.api.Test;
import tools.dscode.common.dataoperations.NeutralRows;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.engine.model.DocText;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.model.Table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tables, doc text, example rows, and mapping on the native engine. Nothing is written to disk. */
class NativeDataExecutionTest {
    public static class DataGlue {
        @Given("native read {string}")
        public String read(String key) {
            Object value = MappingProcessor.getRunMap() == null ? null : MappingProcessor.getRunMap().get(key);
            return value == null ? "" : String.valueOf(value);
        }

        @Given("native table {string}")
        public String table(String name) {
            Object value = MappingProcessor.getRunMap().get(name);
            Table table = assertInstanceOf(Table.class, value);
            return NeutralRows.asMaps(table).toString();
        }

        @Given("native doc {string}")
        public String doc(String name) {
            Object value = MappingProcessor.getRunMap().get(name);
            DocText text = assertInstanceOf(DocText.class, value);
            return text.content();
        }
    }

    @Test
    void exampleRowFeedsTemplatingWithoutInterpolatingTheStepText() {
        String feature = """
                Feature: Outline
                  Scenario Outline: Row
                    * , save "<item>" as "seen"
                    * native read "seen"
                    Examples:
                      | item |
                      | pear |
                """;
        RunResult result = PickleballEngine.run(feature, EngineOptions.of(DataGlue.class));
        assertEquals(Status.PASSED, result.status(), result.toString());
        assertEquals("pear", result.scenarios().getFirst().steps().get(1).returned());
        assertTrue(result.scenarios().getFirst().steps().getFirst().text().contains("<item>"));
    }

    @Test
    void namedDataTableAndDocTextStayNeutral() {
        String feature = """
                Feature: Data
                  Scenario: Store
                    * "cart" DATA TABLE
                      | sku | qty |
                      | a   | 1   |
                    * native table "cart"
                    * "note" DOC STRING
                      \"\"\"
                      hello
                      \"\"\"
                    * native doc "note"
                """;
        RunResult result = PickleballEngine.run(feature, EngineOptions.of(DataGlue.class));
        assertEquals(Status.PASSED, result.status(), result.toString());
        // Passed data-table and doc-string steps are finalizers. Cucumber emits
        // nothing for them, so the native result lists the glue steps that read them.
        assertEquals("[{sku=a, qty=1}]", result.scenarios().getFirst().steps().get(0).returned());
        assertEquals("hello", result.scenarios().getFirst().steps().get(1).returned());
    }

    @Test
    void mapTableValuesResolvesExampleCellsIntoTheRunMap() {
        String feature = """
                Feature: Map
                  Scenario Outline: Cells
                    * MAP TABLE VALUES TO RUN MAP
                      | item | <item> |
                    * native read "item"
                    Examples:
                      | item |
                      | pear |
                """;
        RunResult result = PickleballEngine.run(feature, EngineOptions.of(DataGlue.class));
        assertEquals(Status.PASSED, result.status(), result.toString());
        assertEquals("pear", result.scenarios().getFirst().steps().get(1).returned());
    }
}
