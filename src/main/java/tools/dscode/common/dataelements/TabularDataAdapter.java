package tools.dscode.common.dataelements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.datatable.DataTable;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TabularDataAdapter {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private TabularDataAdapter() {
    }

    public static TabularMatrix adapt(Object source) {
        if (source == null) {
            return TabularMatrix.empty();
        }
        if (source instanceof TabularMatrix matrix) {
            return matrix.copy();
        }
        if (source instanceof DataTable dataTable) {
            return fromDataTable(dataTable);
        }
        if (source instanceof JsonNode jsonNode) {
            return fromJsonNode(jsonNode);
        }
        if (source instanceof Map<?, ?> map) {
            return fromMap(map);
        }
        if (source instanceof Collection<?> collection) {
            return fromCollection(collection);
        }
        if (source.getClass().isArray()) {
            return fromCollection(arrayValues(source));
        }
        return TabularMatrix.fromRows(List.of(List.of(normalizeCell(source))));
    }

    private static TabularMatrix fromDataTable(DataTable dataTable) {
        List<List<Object>> rows = new ArrayList<>();
        for (List<String> row : dataTable.cells()) {
            rows.add(new ArrayList<>(row));
        }
        return TabularMatrix.fromRows(rows);
    }

    private static TabularMatrix fromJsonNode(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return TabularMatrix.empty();
        }
        if (node.isNull()) {
            return TabularMatrix.fromRows(singleCellRow(null));
        }
        if (node instanceof ObjectNode objectNode) {
            return fromObjectNode(objectNode);
        }
        if (!(node instanceof ArrayNode arrayNode)) {
            return TabularMatrix.fromRows(
                    singleCellRow(jsonScalarValue(node))
            );
        }
        if (arrayNode.isEmpty()) {
            return TabularMatrix.empty();
        }
        if (allMatch(arrayNode, JsonNode::isArray)) {
            List<List<Object>> rows = new ArrayList<>(arrayNode.size());
            for (JsonNode rowNode : arrayNode) {
                List<Object> row = new ArrayList<>(rowNode.size());
                rowNode.forEach(value -> row.add(jsonCellValue(value)));
                rows.add(row);
            }
            return TabularMatrix.fromRows(rows);
        }
        if (allMatch(arrayNode, JsonNode::isObject)) {
            return fromObjectNodes(arrayNode);
        }

        List<Object> row = new ArrayList<>(arrayNode.size());
        arrayNode.forEach(value -> row.add(jsonCellValue(value)));
        return TabularMatrix.fromRows(List.of(row));
    }

    private static TabularMatrix fromObjectNode(ObjectNode objectNode) {
        List<Object> headers = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        objectNode.properties().forEach(entry -> {
            headers.add(entry.getKey());
            values.add(jsonCellValue(entry.getValue()));
        });

        if (headers.isEmpty()) {
            return TabularMatrix.empty();
        }
        return TabularMatrix.fromRows(List.of(headers, values));
    }

    private static TabularMatrix fromObjectNodes(ArrayNode rowsNode) {
        Set<String> headers = new LinkedHashSet<>();
        for (JsonNode row : rowsNode) {
            row.fieldNames().forEachRemaining(headers::add);
        }

        if (headers.isEmpty()) {
            return TabularMatrix.empty();
        }

        return matrixPreservingMissing(rowsNode, headers);
    }

    private static TabularMatrix matrixPreservingMissing(
            ArrayNode rowsNode,
            Set<String> headers
    ) {
        List<List<TabularCell>> rows = new ArrayList<>(rowsNode.size() + 1);

        List<TabularCell> headerRow = new ArrayList<>(headers.size());
        headers.forEach(header -> headerRow.add(TabularCell.of(header)));
        rows.add(headerRow);

        for (JsonNode row : rowsNode) {
            List<TabularCell> values = new ArrayList<>(headers.size());
            for (String header : headers) {
                JsonNode value = row.get(header);
                values.add(value == null
                        ? TabularCell.missingCell()
                        : TabularCell.of(jsonCellValue(value)));
            }
            rows.add(values);
        }
        return TabularMatrix.fromCells(rows);
    }

    private static TabularMatrix fromMap(Map<?, ?> map) {
        if (map.isEmpty()) {
            return TabularMatrix.empty();
        }

        List<Object> headers = new ArrayList<>(map.size());
        List<Object> values = new ArrayList<>(map.size());
        map.forEach((key, value) -> {
            headers.add(normalizeCell(key));
            values.add(normalizeCell(value));
        });
        return TabularMatrix.fromRows(List.of(headers, values));
    }

    private static TabularMatrix fromCollection(Collection<?> source) {
        if (source.isEmpty()) {
            return TabularMatrix.empty();
        }

        List<?> values = source instanceof List<?> list
                ? list
                : new ArrayList<>(source);

        if (allMaps(values)) {
            return fromMaps(values);
        }
        if (allRows(values)) {
            List<List<Object>> rows = new ArrayList<>(values.size());
            for (Object value : values) {
                rows.add(collectionValues(value).stream()
                        .map(TabularDataAdapter::normalizeCell)
                        .toList());
            }
            return TabularMatrix.fromRows(rows);
        }

        List<Object> row = values.stream()
                .map(TabularDataAdapter::normalizeCell)
                .toList();
        return TabularMatrix.fromRows(List.of(row));
    }

    private static TabularMatrix fromMaps(List<?> maps) {
        LinkedHashSet<Object> headers = new LinkedHashSet<>();
        for (Object value : maps) {
            ((Map<?, ?>) value).keySet().forEach(headers::add);
        }
        if (headers.isEmpty()) {
            return TabularMatrix.empty();
        }

        List<List<TabularCell>> rows = new ArrayList<>(maps.size() + 1);
        List<TabularCell> headerRow = new ArrayList<>(headers.size());
        headers.forEach(header ->
                headerRow.add(TabularCell.of(normalizeCell(header)))
        );
        rows.add(headerRow);

        for (Object value : maps) {
            Map<?, ?> map = (Map<?, ?>) value;
            List<TabularCell> row = new ArrayList<>(headers.size());
            for (Object header : headers) {
                row.add(map.containsKey(header)
                        ? TabularCell.of(normalizeCell(map.get(header)))
                        : TabularCell.missingCell());
            }
            rows.add(row);
        }
        return TabularMatrix.fromCells(rows);
    }

    private static boolean allMaps(List<?> values) {
        return !values.isEmpty()
                && values.stream().allMatch(Map.class::isInstance);
    }

    private static boolean allRows(List<?> values) {
        return !values.isEmpty()
                && values.stream().allMatch(TabularDataAdapter::isRow);
    }

    private static boolean isRow(Object value) {
        return value instanceof Collection<?>
                || value != null && value.getClass().isArray();
    }

    private static List<?> collectionValues(Object value) {
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return arrayValues(value);
    }

    private static List<Object> arrayValues(Object array) {
        int length = Array.getLength(array);
        List<Object> values = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            values.add(Array.get(array, index));
        }
        return values;
    }

    private static Object normalizeCell(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode jsonNode) {
            return jsonCellValue(jsonNode);
        }
        if (value instanceof Map<?, ?>
                || value instanceof Collection<?>
                || value.getClass().isArray()) {
            return MAPPER.valueToTree(value).toString();
        }
        return value;
    }

    private static Object jsonCellValue(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        if (node.isNull()) {
            return null;
        }
        if (node.isContainerNode()) {
            return node.toString();
        }
        return jsonScalarValue(node);
    }

    private static Object jsonScalarValue(JsonNode node) {
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBinary()) {
            try {
                return node.binaryValue();
            } catch (Exception ignored) {
                return node.asText();
            }
        }
        return node.asText();
    }

    private static boolean allMatch(
            ArrayNode values,
            java.util.function.Predicate<JsonNode> predicate
    ) {
        for (JsonNode value : values) {
            if (!predicate.test(value)) {
                return false;
            }
        }
        return true;
    }

    private static List<List<Object>> singleCellRow(Object value) {
        List<Object> row = new ArrayList<>(1);
        row.add(value);
        return List.of(row);
    }

}
