package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.DataTableType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("unused")
public class DataTableDefinitions {

    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder().build();

    @DataTableType
    public JsonNode jsonNode(DataTable table) {
        List<List<String>> cells = table.cells();
        if (cells.isEmpty()) {
            return JSON_MAPPER.createObjectNode();
        }
        if (cells.size() == 1) {
            return singleRowAsArrayNode(cells.getFirst());
        }

        List<Map<String, String>> rows = rowsAsStringMaps(table);
        if (rows.isEmpty()) {
            return JSON_MAPPER.createObjectNode();
        }
        if (rows.size() == 1) {
            return JSON_MAPPER.valueToTree(rows.getFirst());
        }
        return JSON_MAPPER.valueToTree(rows);
    }

    @DataTableType
    public ObjectNode objectNode(DataTable table) {
        JsonNode node = jsonNode(table);
        if (!(node instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException(
                    "DataTable must convert to a single object node to be converted to ObjectNode, but got: "
                            + describeNode(node)
            );
        }
        return objectNode;
    }

    @DataTableType
    public Set<Object> objectSet(DataTable table) {
        return new LinkedHashSet<>(rowsAsStringMaps(table));
    }

    @DataTableType
    public Set<String> stringSet(DataTable table) {
        return new LinkedHashSet<>(firstColumnValues(table));
    }

    @DataTableType
    public Set<Integer> integerSet(DataTable table) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        List<String> values = firstColumnValues(table);
        for (int i = 0; i < values.size(); i++) {
            result.add(toIntegerValue(values.get(i), "set value at index " + i));
        }
        return result;
    }

    @DataTableType
    public Object object(DataTable table) {
        List<List<String>> cells = table.cells();
        if (cells.size() == 1) {
            return List.copyOf(cells.getFirst());
        }

        List<Map<String, String>> rows = rowsAsStringMaps(table);
        if (rows.isEmpty()) {
            if (table.width() == 1) {
                return List.of();
            }
            return Map.of();
        }
        if (rows.size() == 1) {
            return toObjectMap(rows.getFirst());
        }
        return rows.stream().map(DataTableDefinitions::toObjectMap).toList();
    }

    public static JsonNode dataTableToJsonNode(DataTable table) {
        if (table == null) {
            throw new IllegalArgumentException("DataTable cannot be null");
        }
        return new DataTableDefinitions().jsonNode(table);
    }

    /**
     * A Gherkin table with only one physical row has no body rows under Cucumber's
     * header/data model, so its cell values are exposed as a JSON array instead.
     */
    private static ArrayNode singleRowAsArrayNode(List<String> row) {
        ArrayNode arrayNode = JSON_MAPPER.createArrayNode();
        for (String cell : row) {
            arrayNode.add(cell);
        }
        return arrayNode;
    }

    /**
     * Parse header/body rows from raw cells. Do not call {@link DataTable#asMaps()} from
     * inside a {@link DataTableType} transformer; Cucumber supplies tables with a
     * {@code ConversionRequired} converter that rejects nested conversions.
     * <p>
     * Tables with a single physical row are handled separately via
     * {@link #singleRowAsArrayNode(List)} because Cucumber treats that row as a header
     * with zero data rows.
     */
    private static List<Map<String, String>> rowsAsStringMaps(DataTable table) {
        List<List<String>> cells = table.cells();
        if (cells.size() < 2) {
            return List.of();
        }

        List<String> headers = cells.getFirst();
        List<Map<String, String>> rows = new ArrayList<>(cells.size() - 1);
        for (int rowIndex = 1; rowIndex < cells.size(); rowIndex++) {
            List<String> row = cells.get(rowIndex);
            Map<String, String> mappedRow = new LinkedHashMap<>();
            for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
                String header = headers.get(columnIndex);
                String value = columnIndex < row.size() ? row.get(columnIndex) : "";
                mappedRow.put(header, value);
            }
            rows.add(mappedRow);
        }
        return rows;
    }

    private static List<String> firstColumnValues(DataTable table) {
        List<List<String>> cells = table.cells();
        if (cells.isEmpty()) {
            return List.of();
        }
        if (cells.size() == 1) {
            List<String> row = cells.getFirst();
            return row.isEmpty() ? List.of() : List.of(row.getFirst());
        }
        if (cells.getFirst().isEmpty()) {
            return List.of();
        }

        List<String> values = new ArrayList<>(cells.size() - 1);
        for (int rowIndex = 1; rowIndex < cells.size(); rowIndex++) {
            List<String> row = cells.get(rowIndex);
            values.add(row.isEmpty() ? "" : row.getFirst());
        }
        return values;
    }

    private static Map<String, Object> toObjectMap(Map<String, String> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach(result::put);
        return result;
    }

    private static Integer toIntegerValue(String rawValue, String location) {
        if (rawValue == null) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException(
                    "DataTable " + location + " cannot be converted to Integer, got: " + rawValue
            );
        }
    }

    private static String describeNode(JsonNode node) {
        return node == null ? "null" : node.getNodeType().name();
    }
}