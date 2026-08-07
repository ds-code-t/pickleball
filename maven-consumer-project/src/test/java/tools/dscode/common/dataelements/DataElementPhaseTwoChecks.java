package tools.dscode.common.dataelements;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.datatable.DataTable;
import org.junit.jupiter.api.Test;
import tools.dscode.common.dataoperations.TextOp;
import tools.dscode.common.domoperations.ExecutionDictionary;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.dataelements.DataAttribute.VALUE;
import static tools.dscode.common.dataelements.DataElementForm.PLURAL;
import static tools.dscode.common.dataelements.DataElementForm.SINGULAR;
import static tools.dscode.common.dataelements.DataElementKind.DATA_CELL;
import static tools.dscode.common.dataelements.DataElementKind.DATA_COLUMN;
import static tools.dscode.common.dataelements.DataElementKind.DATA_COLUMN_LIST;
import static tools.dscode.common.dataelements.DataElementKind.DATA_ENTRY;
import static tools.dscode.common.dataelements.DataElementKind.DATA_HEADER;
import static tools.dscode.common.dataelements.DataElementKind.DATA_LIST;
import static tools.dscode.common.dataelements.DataElementKind.DATA_ROW;
import static tools.dscode.common.dataelements.DataElementKind.DATA_TABLE;
import static tools.dscode.common.dataelements.DataElementKind.DATA_VALUE;

public class DataElementPhaseTwoChecks {
    private final DataQueryEngine engine = new DataQueryEngine();


    @Test
    void queryCompilationRejectsPluralBoundariesAndMixedModifiers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DataQuery.builder(DATA_ROW, PLURAL)
                        .first()
                        .build()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> DataQuery.builder(DATA_ROW, SINGULAR)
                        .any()
                        .every()
                        .build()
        );
    }

    @Test
    void nativeDataTableIsPreservedUntilConversionOrModification() {
        DataTable table = duplicateHeaderTable();
        DataContext context = DataContextFactory.table(table);

        DataSelection selection = engine.query(
                context,
                DataQuery.builder(DATA_TABLE, SINGULAR).build()
        );

        assertSame(table, context.nativeSource());
        assertSame(table, context.materializeDeclaredType());
        assertSame(table, selection.materializeTerminal());
        assertFalse(context.modified());
    }

    @Test
    void rowsPreserveDuplicateHeadersInternallyAndMaterializeArrays() {
        DataContext context = DataContextFactory.table(
                duplicateHeaderTable()
        );

        DataSelection selection = engine.query(
                context,
                DataQuery.builder(DATA_ROW, PLURAL).build()
        );

        assertEquals(2, selection.size());
        assertEquals(3, selection.first().entries().size());
        assertEquals("status", selection.first().entries().get(1).key());
        assertEquals("status", selection.first().entries().get(2).key());

        ArrayNode rows = (ArrayNode) selection.materializeTerminal();
        ObjectNode first = (ObjectNode) rows.get(0);
        assertEquals("r1", first.get("id").asText());
        assertTrue(first.get("status").isArray());
        assertEquals("ready", first.get("status").get(0).asText());
        assertEquals("pending", first.get("status").get(1).asText());
    }

    @Test
    void rowFilteringSelectionAndStrideFollowTheQueryContract() {
        DataContext context = DataContextFactory.table(
                duplicateHeaderTable()
        );

        DataSelection first = engine.query(
                context,
                DataQuery.builder(DATA_ROW, SINGULAR).build()
        );
        DataSelection filtered = engine.query(
                context,
                DataQuery.builder(DATA_ROW, SINGULAR)
                        .predicate(TextOp.of(
                                "r2",
                                ExecutionDictionary.Op.EQUALS
                        ))
                        .build()
        );
        DataSelection everySecond = engine.query(
                context,
                DataQuery.builder(DATA_ROW, SINGULAR)
                        .every()
                        .stride(2)
                        .build()
        );

        assertEquals("r1", first.first().key());
        assertEquals("r2", filtered.first().key());
        assertEquals(List.of("r2"), everySecond.candidates().stream()
                .map(DataCandidate::key)
                .toList());

        assertThrows(
                DataQueryException.class,
                () -> engine.query(
                        context,
                        DataQuery.builder(DATA_ROW, SINGULAR)
                                .ordinal(3)
                                .build()
                )
        );
        assertThrows(
                DataQueryException.class,
                () -> engine.query(
                        context,
                        DataQuery.builder(DATA_ROW, SINGULAR)
                                .every()
                                .stride(3)
                                .build()
                )
        );

        DataSelection optionalNoMatch = engine.query(
                context,
                DataQuery.builder(DATA_ROW, PLURAL)
                        .predicate(TextOp.of(
                                "not-present",
                                ExecutionDictionary.Op.EQUALS
                        ))
                        .build()
        );
        assertTrue(optionalNoMatch.isEmpty());

        assertThrows(
                DataQueryException.class,
                () -> engine.query(
                        context,
                        DataQuery.builder(DATA_ROW, SINGULAR)
                                .every()
                                .predicate(TextOp.of(
                                        "not-present",
                                        ExecutionDictionary.Op.EQUALS
                                ))
                                .build()
                )
        );
    }

    @Test
    void columnsAndColumnListsUseTransposedSemantics() {
        DataContext context = DataContextFactory.table(
                duplicateHeaderTable()
        );

        DataSelection columns = engine.query(
                context,
                DataQuery.builder(DATA_COLUMN, PLURAL).build()
        );
        DataSelection columnLists = engine.query(
                context,
                DataQuery.builder(DATA_COLUMN_LIST, PLURAL).build()
        );

        assertEquals(2, columns.size());
        assertEquals(List.of("status", "status"), columns.candidates().stream()
                .map(DataCandidate::key)
                .toList());

        ArrayNode materializedColumns =
                (ArrayNode) columns.materializeTerminal();
        assertEquals(
                "ready",
                materializedColumns.get(0).get("r1").asText()
        );
        assertEquals(
                "pending",
                materializedColumns.get(1).get("r1").asText()
        );

        @SuppressWarnings("unchecked")
        List<List<?>> lists =
                (List<List<?>>) columnLists.materializeTerminal();
        assertEquals(3, lists.size());
        assertEquals(List.of("id", "r1", "r2"), lists.get(0));
        assertEquals(
                List.of("status", "ready", "complete"),
                lists.get(1)
        );
    }

    @Test
    void dataListsAndCellsIncludePhysicalHeaderCells() {
        DataContext context = DataContextFactory.table(
                duplicateHeaderTable()
        );

        DataSelection lists = engine.query(
                context,
                DataQuery.builder(DATA_LIST, PLURAL).build()
        );
        DataSelection cells = engine.query(
                context,
                DataQuery.builder(DATA_CELL, PLURAL).build()
        );

        @SuppressWarnings("unchecked")
        List<List<?>> materializedLists =
                (List<List<?>>) lists.materializeTerminal();
        assertEquals(3, materializedLists.size());
        assertEquals(
                List.of("id", "status", "status"),
                materializedLists.getFirst()
        );

        @SuppressWarnings("unchecked")
        List<Object> materializedCells =
                (List<Object>) cells.materializeTerminal();
        assertEquals(9, materializedCells.size());
        assertEquals("id", materializedCells.getFirst());
        assertEquals("complete", materializedCells.getLast());
        assertEquals(new DataCoordinate(0, 0), cells.first().coordinate());
    }

    @Test
    void entriesHeadersAndValuesRemainOrderedAndRepeated() {
        DataContext context = DataContextFactory.table(
                duplicateHeaderTable()
        );

        DataSelection entries = engine.query(
                context,
                DataQuery.builder(DATA_ENTRY, PLURAL).build()
        );
        DataSelection headers = engine.query(
                context,
                DataQuery.builder(DATA_HEADER, PLURAL).build()
        );
        DataSelection values = engine.query(
                context,
                DataQuery.builder(DATA_VALUE, PLURAL).build()
        );

        assertEquals(6, entries.size());
        assertEquals(
                List.of(
                        "id", "status", "status",
                        "id", "status", "status"
                ),
                headers.candidates().stream()
                        .map(DataCandidate::value)
                        .toList()
        );
        assertEquals(
                List.of(
                        "r1", "ready", "pending",
                        "r2", "complete", "complete"
                ),
                values.candidates().stream()
                        .map(DataCandidate::value)
                        .toList()
        );

        ArrayNode materializedEntries =
                (ArrayNode) entries.materializeTerminal();
        assertEquals(
                "status",
                materializedEntries.get(1)
                        .get("Data Header")
                        .asText()
        );
        assertEquals(
                "ready",
                materializedEntries.get(1)
                        .get("Data Value")
                        .asText()
        );
    }

    @Test
    void comparisonAndReturnAttributesAreIndependent() {
        DataContext context = DataContextFactory.table(
                duplicateHeaderTable()
        );

        DataSelection values = engine.query(
                context,
                DataQuery.builder(DATA_ENTRY, PLURAL)
                        .comparisonAttribute(VALUE)
                        .predicate(TextOp.of(
                                "complete",
                                ExecutionDictionary.Op.EQUALS
                        ))
                        .returnAttribute(VALUE)
                        .build()
        );

        assertEquals(2, values.size());
        assertEquals(
                List.of("complete", "complete"),
                values.materializeTerminal()
        );
    }

    @Test
    void headerOnlyAndOptionalEmptySelectionsHaveDefinedShapes() {
        DataTable headerOnly = DataTable.create(List.of(
                List.of("id", "first", "second")
        ));
        DataContext context = DataContextFactory.table(headerOnly);

        DataSelection rows = engine.query(
                context,
                DataQuery.builder(DATA_ROW, PLURAL).build()
        );
        DataSelection entries = engine.query(
                context,
                DataQuery.builder(DATA_ENTRY, PLURAL).build()
        );
        DataSelection columns = engine.query(
                context,
                DataQuery.builder(DATA_COLUMN, PLURAL).build()
        );
        DataSelection lists = engine.query(
                context,
                DataQuery.builder(DATA_LIST, PLURAL).build()
        );

        assertTrue(rows.isEmpty());
        assertTrue(entries.isEmpty());
        assertEquals(2, columns.size());
        assertEquals(1, lists.size());
        assertTrue(((ArrayNode) rows.materializeTerminal()).isEmpty());

        assertThrows(
                DataQueryException.class,
                () -> engine.query(
                        context,
                        DataQuery.builder(DATA_ROW, SINGULAR).build()
                )
        );
    }

    @Test
    void nullContextIsOptionalForPluralAndRequiredForSingular() {
        DataContext context = DataContextFactory.table(null);

        DataSelection optional = engine.query(
                context,
                DataQuery.builder(DATA_ROW, PLURAL).build()
        );

        assertTrue(optional.isEmpty());
        assertThrows(
                DataQueryException.class,
                () -> engine.query(
                        context,
                        DataQuery.builder(DATA_ROW, SINGULAR).build()
                )
        );
    }

    @Test
    void jsonRaggedRowsPreserveMissingNullBlankAndNestedCells() {
        ArrayNode source = JsonNodeFactory.instance.arrayNode();
        source.add(JsonNodeFactory.instance.arrayNode()
                .add("id")
                .add("payload")
                .add("extra"));
        source.add(JsonNodeFactory.instance.arrayNode()
                .add("r1")
                .add(JsonNodeFactory.instance.objectNode().put("a", 1)));
        source.add(JsonNodeFactory.instance.arrayNode()
                .add("r2")
                .addNull()
                .add(""));

        DataContext context = DataContextFactory.table(source);
        TabularMatrix matrix = context.workingMatrix();

        assertEquals(3, matrix.width());
        assertTrue(matrix.cell(1, 2).missing());
        assertTrue(matrix.cell(2, 1).explicitNull());
        assertEquals("", matrix.cell(2, 2).value());

        DataSelection rows = engine.query(
                context,
                DataQuery.builder(DATA_ROW, PLURAL).build()
        );
        DataSelection cells = engine.query(
                context,
                DataQuery.builder(DATA_CELL, PLURAL).build()
        );
        DataSelection entries = engine.query(
                context,
                DataQuery.builder(DATA_ENTRY, PLURAL).build()
        );

        ArrayNode materializedRows =
                (ArrayNode) rows.materializeTerminal();
        assertEquals(
                "{\"a\":1}",
                materializedRows.get(0).get("payload").asText()
        );
        assertTrue(materializedRows.get(0).get("extra").isNull());
        assertTrue(materializedRows.get(1).get("payload").isNull());
        assertEquals("", materializedRows.get(1).get("extra").asText());

        assertEquals(8, cells.size());
        assertEquals(5, entries.size());
    }

    @Test
    void blankAndMissingHeadersRemainOrderedBeforeReadableMaterialization() {
        DataContext context = DataContextFactory.table(List.of(
                List.of("id", ""),
                List.of("r1", ""),
                List.of("r2", "x", "tail")
        ));

        DataSelection rows = engine.query(
                context,
                DataQuery.builder(DATA_ROW, PLURAL).build()
        );

        assertEquals("", rows.candidates().get(1).entries().get(1).key());
        assertEquals("", rows.candidates().get(1).entries().get(2).key());

        ArrayNode materialized = (ArrayNode) rows.materializeTerminal();
        assertTrue(materialized.get(1).get("").isArray());
        assertEquals("x", materialized.get(1).get("").get(0).asText());
        assertEquals("tail", materialized.get(1).get("").get(1).asText());
    }

    @Test
    void duplicateFirstColumnValuesRemainOrderedInColumnMaterialization() {
        DataContext context = DataContextFactory.table(DataTable.create(List.of(
                List.of("key", "status"),
                List.of("dup", "ready"),
                List.of("dup", "pending")
        )));

        DataSelection columns = engine.query(
                context,
                DataQuery.builder(DATA_COLUMN, PLURAL).build()
        );

        ArrayNode materialized = (ArrayNode) columns.materializeTerminal();
        assertTrue(materialized.get(0).get("dup").isArray());
        assertEquals("ready", materialized.get(0).get("dup").get(0).asText());
        assertEquals("pending", materialized.get(0).get("dup").get(1).asText());
    }

    private static DataTable duplicateHeaderTable() {
        return DataTable.create(List.of(
                List.of("id", "status", "status"),
                List.of("r1", "ready", "pending"),
                List.of("r2", "complete", "complete")
        ));
    }
}
