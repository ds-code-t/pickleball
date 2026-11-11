package tools.dscode.engine.convert;

import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.plugin.event.Status;
import org.junit.jupiter.api.Test;
import tools.dscode.engine.model.DocText;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.ExampleTable;
import tools.dscode.engine.model.SourceLocation;
import tools.dscode.engine.model.StepArgument;
import tools.dscode.engine.model.Table;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CucumberModelConverterTest {

    @Test
    void statusRoundTripsIncludingUnusedAsFailed() {
        for (Status status : Status.values()) {
            tools.dscode.engine.model.Status neutral = CucumberModelConverter.toStatus(status);
            if (status == Status.UNUSED) {
                assertEquals(tools.dscode.engine.model.Status.FAILED, neutral);
                continue;
            }
            assertEquals(status, CucumberModelConverter.toCucumberStatus(neutral));
        }
    }

    @Test
    void dataTableAndDocStringRoundTrip() {
        Table table = new Table(List.of(
                List.of("name", "tier"),
                List.of("Ava", "Premium")
        ));
        DataTable cucumber = CucumberModelConverter.toDataTable(table);
        assertEquals(table, CucumberModelConverter.toTable(cucumber));
        assertEquals(Table.empty(), CucumberModelConverter.toTable(DataTable.emptyDataTable()));
        assertTrue(CucumberModelConverter.toDataTable(Table.empty()).isEmpty());

        DocText doc = new DocText("{\"ok\":true}", "json");
        DocString cucumberDoc = CucumberModelConverter.toDocString(doc);
        assertEquals(doc, CucumberModelConverter.toDocText(cucumberDoc));
        assertEquals(DocText.of("plain"), CucumberModelConverter.toDocText(DocString.create("plain")));
    }

    @Test
    void featureDocumentRoundTripsStepsTablesDocsExamplesAndRules() {
        SourceLocation featureAt = new SourceLocation("memory:round-trip.feature", 1, 0);
        EngineStep background = new EngineStep(
                "Given ",
                "the store is open",
                StepArgument.none(),
                new SourceLocation("memory:round-trip.feature", 3, 5),
                Map.of(),
                true
        );
        EngineStep outlined = new EngineStep(
                "When ",
                "I enter \"<name>\"",
                StepArgument.table(new Table(List.of(List.of("col"), List.of("<name>")))),
                new SourceLocation("memory:round-trip.feature", 8, 5),
                Map.of(),
                false
        );
        EngineScenario outline = new EngineScenario(
                "Scenario Outline",
                "Submit customers",
                "rows stay unsubstituted",
                List.of("@browser"),
                List.of(outlined),
                List.of(new ExampleTable(
                        "people",
                        List.of("@row"),
                        List.of("name"),
                        List.of(List.of("Ava"), List.of("Ben")),
                        new SourceLocation("memory:round-trip.feature", 12, 3)
                )),
                new SourceLocation("memory:round-trip.feature", 6, 3),
                Map.of(EngineScenario.RULE_NAME, "Checkout", "ruleTags", "@rule")
        );
        EngineStep noted = new EngineStep(
                "Then ",
                "the receipt is stored",
                StepArgument.doc(new DocText("thanks", "text")),
                new SourceLocation("memory:round-trip.feature", 20, 5),
                Map.of(),
                false
        );
        EngineScenario plain = new EngineScenario(
                "Scenario",
                "Receipt",
                "",
                List.of("@fast"),
                List.of(noted),
                List.of(),
                new SourceLocation("memory:round-trip.feature", 18, 3),
                Map.of()
        );
        EngineFeature feature = new EngineFeature(
                "Store",
                "A shop",
                "en",
                List.of("@all"),
                List.of(background),
                List.of(outline, plain),
                featureAt
        );

        EngineFeature again = CucumberModelConverter.fromDocument(CucumberModelConverter.toDocument(feature));
        assertEquals(feature, again);
    }

    @Test
    void pickleRoundTripsNameTagsTextArgumentAndKeyword() {
        EngineStep step = new EngineStep(
                "And ",
                ", save \"v\" as \"name\"",
                StepArgument.doc(DocText.of("body")),
                new SourceLocation("memory:one.feature", 4, 5),
                Map.of("origin", "runtime"),
                true
        );
        EngineScenario scenario = new EngineScenario(
                "Scenario",
                "Save a value",
                "ignored by pickle",
                List.of("@native"),
                List.of(step),
                List.of(),
                new SourceLocation("memory:one.feature", 2, 1),
                Map.of()
        );
        EngineScenario again = CucumberModelConverter.fromPickle(
                CucumberModelConverter.toPickle(scenario, "memory:one.feature", "en")
        );
        assertEquals("Save a value", again.name());
        assertEquals(List.of("@native"), again.tags());
        assertEquals(1, again.steps().size());
        EngineStep back = again.steps().getFirst();
        assertEquals("And ", back.keyword());
        assertEquals(", save \"v\" as \"name\"", back.text());
        assertEquals(StepArgument.doc(DocText.of("body")), back.argument());
        assertTrue(back.fromBackground());
    }
}
