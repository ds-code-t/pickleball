package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.datatable.DataTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.coredefinitions.DataTableDefinitions.jsonNodeToDataTable;

public class DataTableConversionChecks {

    @Test
    void convertsArrayOfArraysWithoutInferringAHeader() throws Exception {
        DataTable table = convert("""
                [
                  ["name", "age"],
                  ["Alice", 30],
                  ["Bob", 40]
                ]
                """);

        assertEquals(List.of(
                List.of("name", "age"),
                List.of("Alice", "30"),
                List.of("Bob", "40")
        ), table.cells());
    }

    @Test
    void padsUnevenArrayRowsToTheMaximumWidth() throws Exception {
        DataTable table = convert("""
                [
                  ["a"],
                  ["b", "c"]
                ]
                """);

        assertEquals(List.of(
                List.of("a", ""),
                List.of("b", "c")
        ), table.cells());
    }

    @Test
    void convertsObjectArraysUsingStableFirstSeenHeaderOrder() throws Exception {
        DataTable table = convert("""
                [
                  {"name":"Alice","age":30,"details":{"city":"Phoenix"}},
                  {"name":"Bob","active":true,"tags":["a","b"],"nil":null}
                ]
                """);

        assertEquals(List.of(
                List.of("name", "age", "details", "active", "tags", "nil"),
                List.of("Alice", "30", "{\"city\":\"Phoenix\"}", "", "", ""),
                List.of("Bob", "", "", "true", "[\"a\",\"b\"]", "")
        ), table.cells());
    }

    @Test
    void convertsSingleObjectToHeaderAndValueRows() throws Exception {
        DataTable table = convert("""
                {"name":"Alice","age":30}
                """);

        assertEquals(List.of(
                List.of("name", "age"),
                List.of("Alice", "30")
        ), table.cells());
    }

    @Test
    void convertsFlatAndMixedArraysToOnePhysicalRow() throws Exception {
        assertEquals(
                List.of(List.of("Alice", "30", "true")),
                convert("[\"Alice\",30,true]").cells()
        );
        assertEquals(
                List.of(List.of("Alice", "30", "{\"active\":true}", "[1,2]", "")),
                convert("[\"Alice\",30,{\"active\":true},[1,2],null]").cells()
        );
    }

    @Test
    void convertsScalarToOneCellAndRejectsTopLevelNull() throws Exception {
        assertEquals(
                List.of(List.of("42")),
                convert("42").cells()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> jsonNodeToDataTable(MAPPER.readTree("null"))
        );
    }

    @Test
    void rendersEveryCellTypeConsistently() throws Exception {
        JsonNode node = MAPPER.readTree("""
                [
                  null,
                  "text",
                  7,
                  true,
                  {"nested":"object"},
                  ["nested","array"]
                ]
                """);

        assertEquals(
                List.of(List.of(
                        "",
                        "text",
                        "7",
                        "true",
                        "{\"nested\":\"object\"}",
                        "[\"nested\",\"array\"]"
                )),
                jsonNodeToDataTable(node).cells()
        );
    }

    @Test
    void emptyTopLevelArrayProducesOneBlankCell() throws Exception {
        assertEquals(
                List.of(List.of("")),
                convert("[]").cells()
        );
    }

    private static DataTable convert(String json) throws Exception {
        return jsonNodeToDataTable(MAPPER.readTree(json));
    }
}
