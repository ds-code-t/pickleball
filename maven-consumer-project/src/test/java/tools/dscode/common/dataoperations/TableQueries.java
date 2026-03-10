//package tools.dscode.common.dataoperations;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.node.ArrayNode;
//import com.fasterxml.jackson.databind.node.JsonNodeFactory;
//import com.fasterxml.jackson.databind.node.ObjectNode;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Map;
//
//public final class TableQueries {
//
//    private static final String DATA_ROW = "Data Row";
//    private static final String DATA_CELL = "Data Cell";
//
//    private TableQueries() {}
//
//    public static List<JsonNode> findRows(ObjectNode root) {
//        List<JsonNode> rows = new ArrayList<>();
//        collectRows(root, rows);
//        return rows;
//    }
//
//    public static List<JsonNode> findCells(ObjectNode root) {
//        List<JsonNode> cells = new ArrayList<>();
//        collectExplicitCells(root, cells);
//
//        for (JsonNode row : findRows(root)) {
//            if (row.isObject()) {
//                addRowCells((ObjectNode) row, cells);
//            } else if (row.isArray()) {
//                addArrayElements((ArrayNode) row, cells);
//            }
//        }
//
//        return cells;
//    }
//
//    public static List<String> findCellValues(ObjectNode root) {
//        List<String> values = new ArrayList<>();
//        for (JsonNode cell : findCells(root)) {
//            JsonNode value = isSingleScalarFieldObject(cell) ? cell.elements().next() : cell;
//            values.add(toCellValueString(value));
//        }
//        return values;
//    }
//
//    public static List<String> findHeaders(ObjectNode root) {
//        List<JsonNode> rows = findRows(root);
//        if (rows.isEmpty() || !rows.getFirst().isObject()) {
//            return List.of();
//        }
//
//        List<String> headers = new ArrayList<>();
//        for (Map.Entry<String, JsonNode> entry : ((ObjectNode) rows.getFirst()).properties()) {
//            headers.add(entry.getKey());
//        }
//        return headers;
//    }
//
//    private static void collectRows(JsonNode node, List<JsonNode> rows) {
//        if (node.isObject()) {
//            for (Map.Entry<String, JsonNode> entry : node.properties()) {
//                JsonNode value = entry.getValue();
//
//                if (DATA_ROW.equals(entry.getKey()) && value.isArray()) {
//                    addArrayElements((ArrayNode) value, rows);
//                }
//
//                collectRows(value, rows);
//            }
//            return;
//        }
//
//        if (node.isArray()) {
//            for (JsonNode child : node) {
//                collectRows(child, rows);
//            }
//        }
//    }
//
//    private static void collectExplicitCells(JsonNode node, List<JsonNode> cells) {
//        if (node.isObject()) {
//            for (Map.Entry<String, JsonNode> entry : node.properties()) {
//                JsonNode value = entry.getValue();
//
//                if (DATA_CELL.equals(entry.getKey()) && value.isArray()) {
//                    addArrayElements((ArrayNode) value, cells);
//                }
//
//                collectExplicitCells(value, cells);
//            }
//            return;
//        }
//
//        if (node.isArray()) {
//            for (JsonNode child : node) {
//                collectExplicitCells(child, cells);
//            }
//        }
//    }
//
//    private static void addRowCells(ObjectNode row, List<JsonNode> cells) {
//        for (Map.Entry<String, JsonNode> entry : row.properties()) {
//            JsonNode value = entry.getValue();
//            if (!value.isContainerNode()) {
//                cells.add(cell(entry.getKey(), value));
//            }
//        }
//    }
//
//    private static void addArrayElements(ArrayNode array, List<JsonNode> out) {
//        for (JsonNode item : array) {
//            out.add(item);
//        }
//    }
//
//    private static boolean isSingleScalarFieldObject(JsonNode node) {
//        return node != null
//                && node.isObject()
//                && node.size() == 1
//                && !node.elements().next().isContainerNode();
//    }
//
//    private static String toCellValueString(JsonNode value) {
//        return value == null || value.isMissingNode() || value.isNull() ? "" : value.asText("");
//    }
//
//    private static ObjectNode cell(String key, JsonNode value) {
//        ObjectNode cell = JsonNodeFactory.instance.objectNode();
//        cell.set(key, value.deepCopy());
//        return cell;
//    }
//}