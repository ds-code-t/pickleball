package tools.dscode.common.dataelements;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.datatable.DataTable;
import org.junit.jupiter.api.Test;
import tools.dscode.common.treeparsing.parsedComponents.DataElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatchFactory;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.dataelements.DataAttribute.KEY;
import static tools.dscode.common.dataelements.DataElementForm.PLURAL;
import static tools.dscode.common.dataelements.DataElementForm.SINGULAR;
import static tools.dscode.common.dataelements.DataElementKind.DATA_ENTRY;
import static tools.dscode.common.dataelements.DataElementKind.DATA_ROW;

public final class DataElementPhaseThreeChecks {
    private final DataElementRuntime runtime = new DataElementRuntime();

    @Test
    void singularTerminalReturnsOneMaterializedValue() {
        DataExecutionResult result = runtime.execute(
                table(),
                DataQuery.builder(DATA_ROW, SINGULAR).build()
        );

        TerminalResult terminal =
                assertInstanceOf(TerminalResult.class, result);
        assertEquals(1, terminal.values().size());

        ObjectNode row = assertInstanceOf(
                ObjectNode.class,
                terminal.values().getFirst()
        );
        assertEquals("r1", row.get("id").asText());
        assertEquals("ready", row.get("status").asText());
    }

    @Test
    void pluralTerminalIsOneAggregateValue() {
        DataExecutionResult result = runtime.execute(
                table(),
                DataQuery.builder(DATA_ROW, PLURAL).build()
        );

        TerminalResult terminal =
                assertInstanceOf(TerminalResult.class, result);
        assertEquals(1, terminal.values().size());

        ArrayNode rows = assertInstanceOf(
                ArrayNode.class,
                terminal.values().getFirst()
        );
        assertEquals(3, rows.size());
        assertEquals("r1", rows.get(0).get("id").asText());
        assertEquals("r3", rows.get(2).get("id").asText());
    }

    @Test
    void pluralContextAlsoPreservesOneAggregate() {
        DataExecutionResult result = runtime.execute(
                table(),
                DataQuery.builder(DATA_ROW, PLURAL)
                        .resultUse(DataResultUse.CONTEXT)
                        .build()
        );

        ContextResult context =
                assertInstanceOf(ContextResult.class, result);
        assertEquals(1, context.values().size());
        assertEquals(
                3,
                assertInstanceOf(
                        ArrayNode.class,
                        context.values().getFirst()
                ).size()
        );
    }

    @Test
    void iterationExpandsCandidatesOnlyForIterationMode() {
        DataExecutionResult result = runtime.execute(
                table(),
                DataQuery.builder(DATA_ROW, SINGULAR)
                        .every()
                        .resultUse(DataResultUse.ITERATION)
                        .build()
        );

        IterationResult iteration =
                assertInstanceOf(IterationResult.class, result);
        assertEquals(3, iteration.values().size());
        iteration.values().forEach(value ->
                assertInstanceOf(ObjectNode.class, value)
        );
    }

    @Test
    void optionalIterationCanProduceZeroValues() {
        DataExecutionResult result = runtime.execute(
                headerOnlyTable(),
                DataQuery.builder(DATA_ROW, SINGULAR)
                        .any()
                        .resultUse(DataResultUse.ITERATION)
                        .build()
        );

        IterationResult iteration =
                assertInstanceOf(IterationResult.class, result);
        assertTrue(iteration.values().isEmpty());
    }

    @Test
    void requiredIterationStillRejectsAnEmptySelection() {
        assertThrows(
                DataQueryException.class,
                () -> runtime.execute(
                        headerOnlyTable(),
                        DataQuery.builder(DATA_ROW, SINGULAR)
                                .every()
                                .resultUse(DataResultUse.ITERATION)
                                .build()
                )
        );
    }

    @Test
    void strideIsAppliedBeforeIterationExpansion() {
        DataExecutionResult result = runtime.execute(
                table(),
                DataQuery.builder(DATA_ROW, SINGULAR)
                        .every()
                        .stride(2)
                        .resultUse(DataResultUse.ITERATION)
                        .build()
        );

        IterationResult iteration =
                assertInstanceOf(IterationResult.class, result);
        assertEquals(1, iteration.values().size());
        ObjectNode row = assertInstanceOf(
                ObjectNode.class,
                iteration.values().getFirst()
        );
        assertEquals("r2", row.get("id").asText());
    }

    @Test
    void pluralReturnAttributeIsOneCollectionValue() {
        DataExecutionResult result = runtime.execute(
                table(),
                DataQuery.builder(DATA_ENTRY, PLURAL)
                        .returnAttribute(KEY)
                        .build()
        );

        TerminalResult terminal =
                assertInstanceOf(TerminalResult.class, result);
        assertEquals(1, terminal.values().size());

        List<?> keys = assertInstanceOf(
                List.class,
                terminal.values().getFirst()
        );
        assertEquals(
                List.of("id", "status", "id", "status", "id", "status"),
                keys
        );
    }

    @Test
    void dataElementMatchCompilesPluralAndStrideContracts() {
        MutableElementMatch plural =
                new MutableElementMatch("Data Rows");
        DataElementMatch pluralMatch =
                DataElementMatch.from(plural).orElseThrow();

        assertEquals(PLURAL, pluralMatch.dataQuery().form());
        assertEquals(
                DataCardinality.OPTIONAL_MANY,
                pluralMatch.dataQuery().cardinality()
        );

        MutableElementMatch everySecond =
                new MutableElementMatch("Data Row");
        everySecond.selectionType = "every";
        everySecond.elementPosition = "2nd";

        DataElementMatch strideMatch =
                DataElementMatch.from(everySecond).orElseThrow();
        assertEquals(2, strideMatch.dataQuery().stride());
        assertEquals(
                DataCardinality.REQUIRED_MANY,
                strideMatch.dataQuery().cardinality()
        );
    }

    @Test
    void copyingPreservesTheDataElementSubtypeAndQuery() {
        MutableElementMatch source =
                new MutableElementMatch("Data Rows");
        DataElementMatch dataElement =
                DataElementMatch.from(source).orElseThrow();

        ElementMatch copy = ElementMatchFactory.copy(null, dataElement);

        DataElementMatch copied =
                assertInstanceOf(DataElementMatch.class, copy);
        assertEquals(
                dataElement.dataQuery().kind(),
                copied.dataQuery().kind()
        );
        assertEquals(
                dataElement.dataQuery().form(),
                copied.dataQuery().form()
        );
    }

    private static DataTable table() {
        return DataTable.create(List.of(
                List.of("id", "status"),
                List.of("r1", "ready"),
                List.of("r2", "pending"),
                List.of("r3", "complete")
        ));
    }

    private static DataTable headerOnlyTable() {
        return DataTable.create(List.of(
                List.of("id", "status")
        ));
    }

    private static final class MutableElementMatch
            extends ElementMatch {
        private MutableElementMatch(String category) {
            super(null);
            this.category = category;
            this.state = "";
            this.selectionType = "";
            this.elementPosition = "";
            this.valueTypes = List.of();
            this.elementTypes = ElementType.fromString(category);
            this.isPlaceHolder = false;
        }
    }
}
