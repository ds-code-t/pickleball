package io.cucumber.core.runner.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import io.cucumber.datatable.DataTable;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DataUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String DOCSTRING_KEY = "Doc String";
    public static final String HEADER_KEY = "Header Table";
    public static final String TABLE_KEY = "Data Table";
    public static final String ROW_KEY = "Data Row";
    public static final String COLUMN_KEY = "Data Column";
    public static final String CELL_KEY = "Data Cell";
    public static final String VALUE_KEY = "Data Value";
    public static final String ENTRY_KEY = "Data Entry";
    public static final String NAME_KEY = "Data Name";
    public static final String LIST_KEY = "List";
    public static final String COLUMN_LIST_KEY = "Column List";
    public static final String MAP_KEY = "Map";

    private DataUtils() {
        throw new AssertionError("Utility class");
    }

    //ROW_KEY or //MAP_KEY
    public static ObjectNode toRowsMultimapWithFirstCellKeys(DataTable dataTable) {
        Objects.requireNonNull(dataTable, "dataTable must not be null");

        List<List<String>> table = dataTable.asLists();
        LinkedListMultimap<String, LinkedListMultimap<String, String>> returnMap =
                LinkedListMultimap.create();

        // An empty table or header-only table has no data rows.
        if (table == null || table.size() < 2) {
            return toObjectNode(returnMap);
        }

        List<String> headers = table.getFirst();
        if (headers == null || headers.isEmpty()) {
            throw new IllegalArgumentException(
                    "The DataTable must contain at least one column");
        }

        for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
            List<String> row = requireNonEmptyRow(table.get(rowIndex), rowIndex);
            requireSameSize(headers, row, rowIndex, "header");

            String rowKey = requireJsonFieldName(row.getFirst(),
                    "The first cell of data row " + rowIndex + " must not be null");

            returnMap.put(rowKey, createStringMultimap(headers, row));
        }

        return toObjectNode(returnMap);
    }

    //ROW_KEY or //MAP_KEY
    public static ObjectNode toRowsMultimapWithFirstCellKeys(JsonNode jsonNode) {
        if (jsonNode instanceof ObjectNode objectNode) {
            // An ObjectNode already has the required top-level key/value shape.
            return objectNode;
        }

        if (jsonNode instanceof ArrayNode arrayNode) {
            return arrayToIndexedObject(arrayNode);
        }

        return null;
    }

    //COLUMN_KEY
    public static ObjectNode toColumnsMultimapWithFirstCellKeys(DataTable dataTable) {
        Objects.requireNonNull(dataTable, "dataTable must not be null");

        List<List<String>> table = dataTable.asLists();
        LinkedListMultimap<String, LinkedListMultimap<String, String>> returnMap =
                LinkedListMultimap.create();

        if (table == null || table.isEmpty()) {
            return toObjectNode(returnMap);
        }

        List<String> firstRow = table.getFirst();
        if (firstRow == null || firstRow.size() < 2) {
            return toObjectNode(returnMap);
        }

        int columnCount = firstRow.size();
        validateRectangularTable(table, columnCount);

        // The first column supplies the keys for every nested multimap.
        for (int columnIndex = 1; columnIndex < columnCount; columnIndex++) {
            LinkedListMultimap<String, String> columnMultimap =
                    LinkedListMultimap.create();

            for (int rowIndex = 0; rowIndex < table.size(); rowIndex++) {
                List<String> row = table.get(rowIndex);
                String nestedKey = requireJsonFieldName(row.getFirst(),
                        "The first cell of data row " + rowIndex + " must not be null");
                columnMultimap.put(nestedKey, row.get(columnIndex));
            }

            // The first cell in the value column becomes the outer key.
            String columnKey = requireJsonFieldName(firstRow.get(columnIndex),
                    "The first cell of column " + columnIndex + " must not be null");
            returnMap.put(columnKey, columnMultimap);
        }

        return toObjectNode(returnMap);
    }

    //ENTRY_KEY
    public static ObjectNode toFlattenedEntriesMultimap(DataTable dataTable) {
        return toObjectNode(createFlattenedEntriesMultimap(dataTable));
    }

    //ENTRY_KEY
    public static ObjectNode toFlattenedEntriesMultimap(JsonNode jsonNode) {
        List<ScalarEntry> scalarEntries = findScalarEntries(jsonNode);
        if (scalarEntries == null) {
            return null;
        }

        LinkedListMultimap<String, LinkedListMultimap<String, JsonNode>> returnMap =
                LinkedListMultimap.create();

        for (ScalarEntry scalarEntry : scalarEntries) {
            LinkedListMultimap<String, JsonNode> nestedEntry =
                    LinkedListMultimap.create();
            nestedEntry.put(scalarEntry.key(), scalarEntry.value());
            returnMap.put(scalarEntry.key(), nestedEntry);
        }

        return toObjectNode(returnMap);
    }

    //LIST_KEY
    public static ObjectNode toRowsListWithFirstCellKeys(DataTable dataTable) {
        Objects.requireNonNull(dataTable, "dataTable must not be null");

        List<List<String>> table = dataTable.asLists();
        LinkedListMultimap<String, List<String>> returnMap =
                LinkedListMultimap.create();

        if (table == null || table.isEmpty()) {
            return toObjectNode(returnMap);
        }

        for (int rowIndex = 0; rowIndex < table.size(); rowIndex++) {
            List<String> row = requireNonEmptyRow(table.get(rowIndex), rowIndex);
            String rowKey = requireJsonFieldName(row.getFirst(),
                    "The first cell of data row " + rowIndex + " must not be null");

            // Copy the row so the returned JSON is independent of the DataTable list view.
            returnMap.put(rowKey, new ArrayList<>(row));
        }

        return toObjectNode(returnMap);
    }

    //LIST_KEY
    public static ObjectNode toRowsListWithFirstCellKeys(JsonNode jsonNode) {
        if (jsonNode instanceof ArrayNode arrayNode) {
            return arrayToIndexedObject(arrayNode);
        }

        if (!(jsonNode instanceof ObjectNode objectNode)) {
            return null;
        }

        ObjectNode result = MAPPER.createObjectNode();

        objectNode.fields().forEachRemaining(field -> {
            String fieldName = field.getKey();
            JsonNode fieldValue = field.getValue();

            if (fieldValue instanceof ArrayNode arrayValue) {
                // Array values already have the requested list shape.
                result.set(fieldName, arrayValue);
                return;
            }

            ArrayNode listValue = MAPPER.createArrayNode();

            if (fieldValue instanceof ObjectNode mapValue) {
                // Convert a map to a list containing its direct values.
                mapValue.elements().forEachRemaining(listValue::add);
            } else {
                // Scalars, null, and any other non-array value become one-item lists.
                listValue.add(fieldValue == null ? NullNode.getInstance() : fieldValue);
            }

            result.set(fieldName, listValue);
        });

        return result;
    }

    //COLUMN_LIST_KEY
    public static ObjectNode toColumnsListWithFirstCellKeys(DataTable dataTable) {
        Objects.requireNonNull(dataTable, "dataTable must not be null");

        List<List<String>> table = dataTable.asLists();
        LinkedListMultimap<String, List<String>> returnMap =
                LinkedListMultimap.create();

        if (table == null || table.isEmpty()) {
            return toObjectNode(returnMap);
        }

        List<String> firstRow = table.getFirst();
        if (firstRow == null || firstRow.isEmpty()) {
            return toObjectNode(returnMap);
        }

        int columnCount = firstRow.size();
        validateRectangularTable(table, columnCount);

        for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
            String columnKey = requireJsonFieldName(firstRow.get(columnIndex),
                    "The first cell of column " + columnIndex + " must not be null");
            List<String> column = new ArrayList<>(table.size());

            for (List<String> row : table) {
                column.add(row.get(columnIndex));
            }

            returnMap.put(columnKey, column);
        }

        return toObjectNode(returnMap);
    }

    // CELL_KEY
    public static ObjectNode toCellsWithCellValueKey(DataTable dataTable) {
        Objects.requireNonNull(dataTable, "dataTable must not be null");

        LinkedListMultimap<String, String> returnMap =
                LinkedListMultimap.create();

        List<List<String>> table = dataTable.asLists();
        if (table == null || table.isEmpty()) {
            return toObjectNode(returnMap);
        }

        for (int rowIndex = 0; rowIndex < table.size(); rowIndex++) {
            List<String> row = table.get(rowIndex);
            if (row == null) {
                throw new IllegalArgumentException(
                        "Data row " + rowIndex + " must not be null");
            }

            for (int columnIndex = 0; columnIndex < row.size(); columnIndex++) {
                String cellValue = requireJsonFieldName(row.get(columnIndex),
                        "Cell [" + rowIndex + ", " + columnIndex + "] must not be null");
                returnMap.put(cellValue, cellValue);
            }
        }

        return toObjectNode(returnMap);
    }

    // CELL_KEY
    public static ObjectNode toCellsWithCellValueKey(JsonNode jsonNode) {
        List<ScalarEntry> scalarEntries = findScalarEntries(jsonNode);
        if (scalarEntries == null) {
            return null;
        }

        LinkedListMultimap<String, Object> returnMap =
                LinkedListMultimap.create();

        for (ScalarEntry scalarEntry : scalarEntries) {
            String entryKey = scalarEntry.key();
            JsonNode entryValue = scalarEntry.value();

            // Include both the matched key and its matched scalar value as cells.
            returnMap.put(entryKey, entryKey);
            returnMap.put(scalarToFieldName(entryValue), entryValue);
        }

        return toObjectNode(returnMap);
    }

    //NAME_KEY
    public static ObjectNode toFlattenedKeys(DataTable dataTable) {
        LinkedListMultimap<String, LinkedListMultimap<String, String>> flattenedEntries =
                createFlattenedEntriesMultimap(dataTable);

        LinkedListMultimap<String, String> returnMap =
                LinkedListMultimap.create();

        for (LinkedListMultimap<String, String> nestedMultimap
                : flattenedEntries.values()) {
            for (Map.Entry<String, String> entry : nestedMultimap.entries()) {
                String key = requireJsonFieldName(entry.getKey(),
                        "A flattened entry key must not be null");
                returnMap.put(key, key);
            }
        }

        return toObjectNode(returnMap);
    }

    //NAME_KEY
    public static ObjectNode toFlattenedKeys(JsonNode jsonNode) {
        List<ScalarEntry> scalarEntries = findScalarEntries(jsonNode);
        if (scalarEntries == null) {
            return null;
        }

        LinkedListMultimap<String, String> returnMap =
                LinkedListMultimap.create();

        for (ScalarEntry scalarEntry : scalarEntries) {
            returnMap.put(scalarEntry.key(), scalarEntry.key());
        }

        return toObjectNode(returnMap);
    }

    //VALUE_KEY
    public static ObjectNode toFlattenedValues(DataTable dataTable) {
        LinkedListMultimap<String, LinkedListMultimap<String, String>> flattenedEntries =
                createFlattenedEntriesMultimap(dataTable);

        LinkedListMultimap<String, String> returnMap =
                LinkedListMultimap.create();

        for (LinkedListMultimap<String, String> nestedMultimap
                : flattenedEntries.values()) {
            for (Map.Entry<String, String> entry : nestedMultimap.entries()) {
                String value = requireJsonFieldName(entry.getValue(),
                        "A flattened entry value must not be null");
                returnMap.put(value, value);
            }
        }

        return toObjectNode(returnMap);
    }

    //VALUE_KEY
    public static ObjectNode toFlattenedValues(JsonNode jsonNode) {
        List<ScalarEntry> scalarEntries = findScalarEntries(jsonNode);
        if (scalarEntries == null) {
            return null;
        }

        LinkedListMultimap<String, JsonNode> returnMap =
                LinkedListMultimap.create();

        for (ScalarEntry scalarEntry : scalarEntries) {
            JsonNode value = scalarEntry.value();
            returnMap.put(scalarToFieldName(value), value);
        }

        return toObjectNode(returnMap);
    }

    /**
     * Creates an ObjectNode from paired key and value lists. Duplicate keys are
     * retained by storing all values for each property in an ArrayNode.
     */
    public static ObjectNode toMultimap(List<?> keys, List<?> values) {
        if (keys == null || values == null) {
            throw new IllegalArgumentException(
                    "Keys and values must not be null");
        }

        if (keys.size() != values.size()) {
            throw new IllegalArgumentException(
                    "Keys and values must have the same size");
        }

        LinkedListMultimap<String, Object> multimap =
                LinkedListMultimap.create();

        for (int index = 0; index < keys.size(); index++) {
            Object key = keys.get(index);
            if (key == null) {
                throw new IllegalArgumentException(
                        "Key at index " + index + " must not be null");
            }

            multimap.put(String.valueOf(key), values.get(index));
        }

        return toObjectNode(multimap);
    }

    /**
     * Recursively converts a LinkedListMultimap to an ObjectNode.
     *
     * Each distinct multimap key becomes one JSON property. The property value
     * is always an ArrayNode containing every value associated with that key,
     * so duplicate mappings are never overwritten. A nested
     * LinkedListMultimap is recursively converted to a nested ObjectNode.
     */
    public static ObjectNode toObjectNode(LinkedListMultimap<?, ?> multimap) {
        Objects.requireNonNull(multimap, "multimap must not be null");

        ObjectNode result = MAPPER.createObjectNode();

        multimap.asMap().forEach((key, values) -> {
            if (key == null) {
                throw new IllegalArgumentException(
                        "A null multimap key cannot be used as a JSON property name");
            }

            String fieldName = String.valueOf(key);
            ArrayNode valueArray = getOrCreateArray(result, fieldName);

            for (Object value : values) {
                valueArray.add(toJsonNode(value));
            }
        });

        return result;
    }

    private static LinkedListMultimap<String, LinkedListMultimap<String, String>>
    createFlattenedEntriesMultimap(DataTable dataTable) {
        Objects.requireNonNull(dataTable, "dataTable must not be null");

        List<List<String>> table = dataTable.asLists();
        LinkedListMultimap<String, LinkedListMultimap<String, String>> returnMap =
                LinkedListMultimap.create();

        // An empty table or a header-only table has no entries.
        if (table == null || table.size() < 2) {
            return returnMap;
        }

        List<String> headers = table.getFirst();
        if (headers == null || headers.isEmpty()) {
            throw new IllegalArgumentException(
                    "The DataTable must contain at least one column");
        }

        for (int rowIndex = 1; rowIndex < table.size(); rowIndex++) {
            List<String> row = table.get(rowIndex);
            if (row == null) {
                throw new IllegalArgumentException(
                        "Data row " + rowIndex + " must not be null");
            }

            requireSameSize(headers, row, rowIndex, "header");

            for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
                String entryKey = requireJsonFieldName(headers.get(columnIndex),
                        "Header cell " + columnIndex + " must not be null");
                String entryValue = row.get(columnIndex);

                LinkedListMultimap<String, String> nestedEntry =
                        LinkedListMultimap.create();
                nestedEntry.put(entryKey, entryValue);

                returnMap.put(entryKey, nestedEntry);
            }
        }

        return returnMap;
    }

    private static LinkedListMultimap<String, String> createStringMultimap(
            List<String> keys,
            List<String> values) {
        Objects.requireNonNull(keys, "keys must not be null");
        Objects.requireNonNull(values, "values must not be null");

        if (keys.size() != values.size()) {
            throw new IllegalArgumentException(
                    "Keys and values must have the same size");
        }

        LinkedListMultimap<String, String> multimap =
                LinkedListMultimap.create();

        for (int index = 0; index < keys.size(); index++) {
            String key = requireJsonFieldName(keys.get(index),
                    "Key at index " + index + " must not be null");
            multimap.put(key, values.get(index));
        }

        return multimap;
    }

    /**
     * Finds every scalar entry at any nesting depth.
     *
     * Object scalar values use their property names as keys. Scalar values that
     * are direct items of an ArrayNode use their numeric indexes as keys. Null
     * nodes count as scalar values. Structured values are traversed recursively.
     */
    private static List<ScalarEntry> findScalarEntries(JsonNode jsonNode) {
        if (jsonNode == null || !jsonNode.isContainerNode()) {
            return null;
        }

        List<ScalarEntry> scalarEntries = new ArrayList<>();
        collectScalarEntries(jsonNode, scalarEntries);
        return scalarEntries;
    }

    private static void collectScalarEntries(
            JsonNode jsonNode,
            List<ScalarEntry> scalarEntries) {

        if (jsonNode instanceof ObjectNode objectNode) {
            objectNode.fields().forEachRemaining(field -> {
                JsonNode value = field.getValue();
                if (isScalar(value)) {
                    scalarEntries.add(new ScalarEntry(field.getKey(), value));
                } else if (value != null && value.isContainerNode()) {
                    collectScalarEntries(value, scalarEntries);
                }
            });
            return;
        }

        if (jsonNode instanceof ArrayNode arrayNode) {
            for (int index = 0; index < arrayNode.size(); index++) {
                JsonNode value = arrayNode.get(index);
                if (isScalar(value)) {
                    scalarEntries.add(new ScalarEntry(String.valueOf(index), value));
                } else if (value != null && value.isContainerNode()) {
                    collectScalarEntries(value, scalarEntries);
                }
            }
        }
    }

    private static boolean isScalar(JsonNode jsonNode) {
        return jsonNode != null
                && jsonNode.isValueNode()
                && !jsonNode.isMissingNode();
    }

    private static String scalarToFieldName(JsonNode scalarValue) {
        if (!isScalar(scalarValue)) {
            throw new IllegalArgumentException(
                    "Only scalar JsonNode values can be converted to property names");
        }

        if (scalarValue.isNull()) {
            return "null";
        }

        if (scalarValue.isTextual()) {
            return scalarValue.textValue();
        }

        return scalarValue.asText();
    }

    private static ObjectNode arrayToIndexedObject(ArrayNode arrayNode) {
        ObjectNode result = MAPPER.createObjectNode();

        for (int index = 0; index < arrayNode.size(); index++) {
            result.set(String.valueOf(index), arrayNode.get(index));
        }

        return result;
    }

    private static JsonNode toJsonNode(Object value) {
        if (value == null) {
            return NullNode.getInstance();
        }

        if (value instanceof LinkedListMultimap<?, ?> nestedMultimap) {
            return toObjectNode(nestedMultimap);
        }

        if (value instanceof JsonNode jsonNode) {
            return jsonNode.deepCopy();
        }

        if (value instanceof Map<?, ?> map) {
            ObjectNode objectNode = MAPPER.createObjectNode();
            map.forEach((mapKey, mapValue) -> {
                if (mapKey == null) {
                    throw new IllegalArgumentException(
                            "A null map key cannot be used as a JSON property name");
                }
                objectNode.set(String.valueOf(mapKey), toJsonNode(mapValue));
            });
            return objectNode;
        }

        if (value instanceof Iterable<?> iterable) {
            ArrayNode arrayNode = MAPPER.createArrayNode();
            for (Object item : iterable) {
                arrayNode.add(toJsonNode(item));
            }
            return arrayNode;
        }

        if (value.getClass().isArray()) {
            ArrayNode arrayNode = MAPPER.createArrayNode();
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                arrayNode.add(toJsonNode(Array.get(value, index)));
            }
            return arrayNode;
        }

        return MAPPER.valueToTree(value);
    }

    private static ArrayNode getOrCreateArray(
            ObjectNode objectNode,
            String fieldName) {
        JsonNode existing = objectNode.get(fieldName);

        if (existing == null) {
            return objectNode.putArray(fieldName);
        }

        if (existing instanceof ArrayNode arrayNode) {
            return arrayNode;
        }

        throw new IllegalStateException(
                "JSON property '" + fieldName + "' is not an array");
    }

    private static List<String> requireNonEmptyRow(
            List<String> row,
            int rowIndex) {
        if (row == null || row.isEmpty()) {
            throw new IllegalArgumentException(
                    "Data row " + rowIndex + " must contain at least one cell");
        }
        return row;
    }

    private static void requireSameSize(
            List<?> expected,
            List<?> actual,
            int rowIndex,
            String expectedName) {
        if (actual.size() != expected.size()) {
            throw new IllegalArgumentException(
                    "Data row " + rowIndex
                            + " contains " + actual.size()
                            + " cells, but the " + expectedName
                            + " contains " + expected.size() + " cells");
        }
    }

    private static void validateRectangularTable(
            List<List<String>> table,
            int columnCount) {
        for (int rowIndex = 0; rowIndex < table.size(); rowIndex++) {
            List<String> row = table.get(rowIndex);
            if (row == null) {
                throw new IllegalArgumentException(
                        "Data row " + rowIndex + " must not be null");
            }

            if (row.size() != columnCount) {
                throw new IllegalArgumentException(
                        "Data row " + rowIndex
                                + " contains " + row.size()
                                + " cells, but the first row contains "
                                + columnCount + " cells");
            }
        }
    }

    private static String requireJsonFieldName(
            String value,
            String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private record ScalarEntry(String key, JsonNode value) {
        private ScalarEntry {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(value, "value must not be null");
        }
    }
}
