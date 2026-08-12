package tools.dscode.common.dataelements;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import io.cucumber.datatable.DataTable;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static tools.dscode.common.dataelements.DataElementForm.PLURAL;
import static tools.dscode.common.dataelements.DataElementForm.SINGULAR;
import static tools.dscode.common.dataelements.DataElementKind.DATA_ROW;
import static tools.dscode.common.dataelements.DataElementKind.DATA_TABLE;
import static tools.dscode.common.dataelements.DataElementKind.LIST;
import static tools.dscode.common.dataelements.DataElementKind.MAP;
import static tools.dscode.common.dataelements.DataElementKind.MULTIMAP;
import static tools.dscode.common.dataelements.DataElementKind.SET;
import static tools.dscode.common.dataelements.DataResultUse.CONTEXT;
import static tools.dscode.common.dataelements.DataResultUse.ITERATION;

public final class DataElementPhaseFiveChecks {
    private final DataElementRuntime runtime = new DataElementRuntime();

    @Test
    void tableWritesAreCopyOnWriteAndIndependent() {
        DataTable source = table();

        DataContextNodeMap first = context(
                runtime.execute(
                        source,
                        DataQuery.builder(DATA_TABLE, SINGULAR)
                                .resultUse(CONTEXT)
                                .build()
                )
        );
        DataContextNodeMap second = context(
                runtime.execute(
                        source,
                        DataQuery.builder(DATA_TABLE, SINGULAR)
                                .resultUse(CONTEXT)
                                .build()
                )
        );

        first.put("1.1", "working");

        DataTable modified = assertInstanceOf(
                DataTable.class,
                first.materialize()
        );
        assertNotSame(source, modified);
        assertEquals("working", modified.cells().get(1).get(1));
        assertEquals("ready", source.cells().get(1).get(1));
        assertSame(source, second.materialize());
    }

    @Test
    void rowIterationWritesShareOneOwnerAndPreserveDuplicates() {
        DataTable source = table();
        IterationResult result = assertInstanceOf(
                IterationResult.class,
                runtime.execute(
                        source,
                        DataQuery.builder(DATA_ROW, PLURAL)
                                .every()
                                .resultUse(ITERATION)
                                .build()
                )
        );

        List<?> values = DataContextNodeMap.contextualValues(result);
        DataContextNodeMap first = assertInstanceOf(
                DataContextNodeMap.class,
                values.get(0)
        );
        DataContextNodeMap second = assertInstanceOf(
                DataContextNodeMap.class,
                values.get(1)
        );

        first.put("status.0", "working");
        second.put("status.1", "blocked");

        DataTable modified = assertInstanceOf(
                DataTable.class,
                result.selection().context().materializeDeclaredType()
        );
        assertEquals(
                List.of("id", "status", "status"),
                modified.cells().getFirst()
        );
        assertEquals(
                List.of("r1", "working", "pending"),
                modified.cells().get(1)
        );
        assertEquals(
                List.of("r2", "ready", "blocked"),
                modified.cells().get(2)
        );
        assertEquals(
                List.of("r1", "ready", "pending"),
                source.cells().get(1)
        );

        DataContextNodeMap enclosing =
                DataContextNodeMap.forSelection(result.selection());
        ArrayNode rows = assertInstanceOf(
                ArrayNode.class,
                enclosing.materialize()
        );
        assertEquals(
                "working",
                rows.get(0).get("status").get(0).asText()
        );
        assertEquals(
                "blocked",
                rows.get(1).get("status").get(1).asText()
        );
    }

    @Test
    void listAndMapReplacementKeepOriginalsUnchanged() {
        List<String> sourceList = List.of("alpha", "beta");
        DataContextNodeMap list = context(
                runtime.execute(
                        sourceList,
                        DataQuery.builder(LIST, SINGULAR)
                                .resultUse(CONTEXT)
                                .build()
                )
        );
        list.put("1", "changed");

        assertEquals(
                List.of("alpha", "changed"),
                list.materialize()
        );
        assertEquals(List.of("alpha", "beta"), sourceList);

        Map<String, String> sourceMap = new LinkedHashMap<>();
        sourceMap.put("id", "r1");
        sourceMap.put("status", "ready");
        DataContextNodeMap map = context(
                runtime.execute(
                        sourceMap,
                        DataQuery.builder(MAP, SINGULAR)
                                .resultUse(CONTEXT)
                                .build()
                )
        );
        map.put("status", "working");

        Map<?, ?> modified = assertInstanceOf(
                Map.class,
                map.materialize()
        );
        assertEquals(
                List.of("id", "status"),
                List.copyOf(modified.keySet())
        );
        assertEquals("working", modified.get("status"));
        assertEquals("ready", sourceMap.get("status"));
    }

    @Test
    void multimapReplacementPreservesGlobalAndDuplicateOrder() {
        LinkedListMultimap<String, String> source =
                LinkedListMultimap.create();
        source.put("status", "ready");
        source.put("status", "ready");
        source.put("status", "pending");
        source.put("owner", "team");

        DataContextNodeMap context = context(
                runtime.execute(
                        source,
                        DataQuery.builder(MULTIMAP, SINGULAR)
                                .resultUse(CONTEXT)
                                .build()
                )
        );
        context.put("status.1", "working");

        Multimap<?, ?> modified = assertInstanceOf(
                Multimap.class,
                context.materialize()
        );
        assertEquals(
                List.of("status", "status", "status", "owner"),
                List.copyOf(modified.keys())
        );
        assertEquals(
                List.of("ready", "working", "pending", "team"),
                List.copyOf(modified.values())
        );
        assertEquals(
                List.of("ready", "ready", "pending", "team"),
                List.copyOf(source.values())
        );
    }

    @Test
    void compatibilityProjectionCannotMutateTheOwner() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("status", "ready");

        DataContextNodeMap context = context(
                runtime.execute(
                        source,
                        DataQuery.builder(MAP, SINGULAR)
                                .resultUse(CONTEXT)
                                .build()
                )
        );
        ObjectNode projection = context.getRoot();
        projection.put("status", "projection-only");

        Map<?, ?> materialized = assertInstanceOf(
                Map.class,
                context.materialize()
        );
        assertEquals("ready", materialized.get("status"));
    }

    @Test
    void structuralMutationRemainsExplicitlyUnsupported() {
        DataContextNodeMap list = context(
                runtime.execute(
                        List.of("one", "two"),
                        DataQuery.builder(LIST, SINGULAR)
                                .resultUse(CONTEXT)
                                .build()
                )
        );
        assertThrows(
                DataQueryException.class,
                () -> list.put("2", "three")
        );
        assertThrows(
                DataQueryException.class,
                list::clearValues
        );

        DataContextNodeMap map = context(
                runtime.execute(
                        Map.of("status", "ready"),
                        DataQuery.builder(MAP, SINGULAR)
                                .resultUse(CONTEXT)
                                .build()
                )
        );
        assertThrows(
                DataQueryException.class,
                () -> map.put("owner", "team")
        );

        DataContextNodeMap set = context(
                runtime.execute(
                        java.util.Set.of("one", "two"),
                        DataQuery.builder(SET, SINGULAR)
                                .resultUse(CONTEXT)
                                .build()
                )
        );
        assertThrows(
                DataQueryException.class,
                () -> set.put("value", "changed")
        );
    }

    @Test
    void terminalResultsRemainNormalPublicValues() {
        TerminalResult result = assertInstanceOf(
                TerminalResult.class,
                runtime.execute(
                        List.of("one", "two"),
                        DataQuery.builder(LIST, SINGULAR).build()
                )
        );

        List<?> values = DataContextNodeMap.contextualValues(result);
        assertSame(result.values(), values);
        assertInstanceOf(List.class, values.getFirst());
    }

    private static DataContextNodeMap context(
            DataExecutionResult result
    ) {
        List<?> values = DataContextNodeMap.contextualValues(result);
        assertEquals(1, values.size());
        return assertInstanceOf(
                DataContextNodeMap.class,
                values.getFirst()
        );
    }

    private static DataTable table() {
        return DataTable.create(List.of(
                List.of("id", "status", "status"),
                List.of("r1", "ready", "pending"),
                List.of("r2", "ready", "pending")
        ));
    }
}
