package tools.dscode.common.dataoperations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.cucumber.core.runner.util.TableUtils.CELL_KEY;
import static io.cucumber.core.runner.util.TableUtils.ROW_KEY;
import static tools.dscode.common.GlobalConstants.META_FLAG;

public final class TableQueries {


    private TableQueries() {}

    public static List<JsonNode> findRows(ObjectNode root) {
        List<JsonNode> rows = new ArrayList<>();
        collectRows(root, rows);
        return rows;
    }

    public static List<JsonNode> findCells(ObjectNode root) {
        List<JsonNode> cells = new ArrayList<>();
        collectExplicitCells(root, cells);
        return cells;
    }

    public static List<String> findCellValues(ObjectNode root) {
        List<String> values = new ArrayList<>();
        for (JsonNode cell : findCells(root)) {
            JsonNode value = isSingleScalarFieldObject(cell) ? cell.elements().next() : cell;
            values.add(toCellValueString(value));
        }
        return values;
    }

    public static List<String> findHeaders(ObjectNode root) {
        List<JsonNode> rows = findRows(root);
        if (rows.isEmpty() || !rows.getFirst().isObject()) {
            return List.of();
        }

        List<String> headers = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : ((ObjectNode) rows.getFirst()).properties()) {
            headers.add(entry.getKey());
        }
        return headers;
    }

    private static void collectRows(JsonNode node, List<JsonNode> rows) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                JsonNode value = entry.getValue();

                if (ROW_KEY.equals(entry.getKey()) && value.isArray()) {
                    addArrayElements((ArrayNode) value, rows);
                }

                collectRows(value, rows);
            }
            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                collectRows(child, rows);
            }
        }
    }

    private static void collectExplicitCells(JsonNode node, List<JsonNode> cells) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;

            for (Map.Entry<String, JsonNode> entry : obj.properties()) {
                String key = entry.getKey();
                JsonNode value = entry.getValue();

                if (!key.contains(META_FLAG)
                        && value.isArray()
                        && !value.isEmpty()
                        && allScalar((ArrayNode) value)) {
                    for (JsonNode item : value) {
                        cells.add(cell(key, item));
                    }
                }

                collectExplicitCells(value, cells);
            }
            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                collectExplicitCells(child, cells);
            }
        }
    }

    private static boolean allScalar(ArrayNode array) {
        for (JsonNode item : array) {
            if (item == null || item.isContainerNode()) {
                return false;
            }
        }
        return true;
    }

    private static void addRowCells(ObjectNode row, List<JsonNode> cells) {
        for (Map.Entry<String, JsonNode> entry : row.properties()) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();

            if (key.contains(META_FLAG)) {
                continue;
            }

            if (!value.isContainerNode()) {
                cells.add(cell(key, value));
            } else if (value.isArray() && value.size() == 1 && !value.get(0).isContainerNode()) {
                cells.add(cell(key, value.get(0)));
            }
        }
    }

    private static void addArrayElements(ArrayNode array, List<JsonNode> out) {
        for (JsonNode item : array) {
            out.add(item);
        }
    }

    private static boolean isSingleScalarFieldObject(JsonNode node) {
        return node != null
                && node.isObject()
                && node.size() == 1
                && !node.elements().next().isContainerNode();
    }

    private static String toCellValueString(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static ObjectNode cell(String key, JsonNode value) {
        ObjectNode cell = JsonNodeFactory.instance.objectNode();
        cell.set(key, value.deepCopy());
        return cell;
    }
}