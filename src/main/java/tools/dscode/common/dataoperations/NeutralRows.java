package tools.dscode.common.dataoperations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.engine.model.DocText;
import tools.dscode.engine.model.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Table and doc-text operations on the neutral model. Cucumber DataTable and
 * DocString are not used here.
 */
public final class NeutralRows {
    private NeutralRows() {
    }

    public static List<String> headers(Table table) {
        if (table == null || table.isEmpty()) return List.of();
        return table.rows().getFirst();
    }

    public static List<Map<String, String>> asMaps(Table table) {
        if (table == null || table.rows().size() < 2) return List.of();
        List<String> headers = headers(table);
        List<Map<String, String>> maps = new ArrayList<>();
        for (int index = 1; index < table.rows().size(); index++) {
            List<String> row = table.rows().get(index);
            Map<String, String> mapped = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                String value = column < row.size() ? row.get(column) : "";
                mapped.put(headers.get(column), value == null ? "" : value);
            }
            maps.add(mapped);
        }
        return List.copyOf(maps);
    }

    public static JsonNode toJson(Table table) {
        List<Map<String, String>> maps = asMaps(table);
        if (maps.isEmpty()) return JsonNodeFactory.instance.arrayNode();
        if (maps.size() == 1) return objectOf(maps.getFirst());
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (Map<String, String> row : maps) array.add(objectOf(row));
        return array;
    }

    public static String text(DocText doc) {
        return doc == null ? "" : doc.content();
    }

    public static boolean same(Table left, Table right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.rows().equals(right.rows());
    }

    public static ObjectNode objectOf(Map<String, String> row) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (row != null) row.forEach(node::put);
        return node;
    }
}
