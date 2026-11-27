package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only element object. {@code find}, {@code snap}, {@code act}, and desktop query call this.
 * The eight canonical keys are always present. A row that already has {@code ref}, visibility,
 * {@code via}, a collaboration key, {@code shadow}, or {@code shadow_path} keeps that key.
 * Other backend fields sit under {@code extra} and are omitted unless {@code --fields} names them.
 */
public final class ElementRecord {
    static final List<String> KEYS = List.of(
            "handle", "identity", "role", "name", "tag", "bounds", "backendNodeId", "frame"
    );
    /** Public row fields copied when the source already has them. Absent keys stay absent. */
    private static final List<String> ROW_FIELDS = List.of(
            "ref", "visible", "enabled", "interactive", "via",
            "value", "checked", "purpose", "badge", "highlight", "guide", "card", "msg",
            "shadow", "shadow_path"
    );
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CANONICAL = Set.copyOf(KEYS);
    private static final Set<String> ROW = Set.copyOf(ROW_FIELDS);

    private ElementRecord() {
    }

    public static List<Map<String, Object>> list(List<Map<String, Object>> rows, String fields) {
        List<Map<String, Object>> records = new ArrayList<>();
        if (rows == null) return records;
        for (Map<String, Object> row : rows) records.add(from(row, fields));
        return records;
    }

    public static Map<String, Object> from(Map<String, Object> source, String fields) {
        Map<String, Object> raw = source == null ? Map.of() : source;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("handle", handle(raw));
        record.put("identity", identity(raw));
        record.put("role", text(first(raw, "role")));
        record.put("name", text(first(raw, "name", "accessibleName", "text")));
        record.put("tag", text(first(raw, "tag", "type", "node_name", "nodeName")));
        record.put("bounds", bounds(raw));
        record.put("backendNodeId", backend(raw));
        record.put("frame", frame(raw));
        copyPresent(record, raw);
        Map<String, Object> extra = extra(raw, fields);
        if (!extra.isEmpty()) record.put("extra", extra);
        return record;
    }

    /** Keeps a public field the row already stored. {@code false} is kept. A missing key is not invented. */
    private static void copyPresent(Map<String, Object> record, Map<String, Object> raw) {
        for (String key : ROW_FIELDS) {
            if (!raw.containsKey(key) || raw.get(key) == null) continue;
            record.put(key, raw.get(key));
        }
    }

    /** Desktop query nodes become records. The rest of the document stays. A non-query document is unchanged. */
    public static String rewriteDesktopQuery(String json, String fields) {
        if (json == null || json.isBlank()) return json;
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isObject()) return json;
            ObjectNode copy = root.deepCopy();
            rewriteArray(copy, "nodes", fields);
            rewriteArray(copy, "composite", fields);
            return JSON.writeValueAsString(copy);
        } catch (Exception failure) {
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    private static void rewriteArray(ObjectNode root, String key, String fields) {
        JsonNode array = root.get(key);
        if (array == null || !array.isArray()) return;
        ArrayNode replacement = JSON.createArrayNode();
        for (JsonNode node : array) {
            if (!node.isObject()) {
                replacement.add(node);
                continue;
            }
            Map<String, Object> raw = JSON.convertValue(node, LinkedHashMap.class);
            replacement.add(JSON.valueToTree(from(raw, fields)));
        }
        root.set(key, replacement);
    }

    private static String handle(Map<String, Object> raw) {
        Object id = raw.get("id");
        if (id instanceof String text && text.matches("s\\d+\\.h\\d+")) return text;
        Object handle = first(raw, "handle", "id", "backendNodeId", "backend_node_id");
        return handle == null ? "" : String.valueOf(handle);
    }

    private static Map<String, Object> identity(Map<String, Object> raw) {
        List<Object> chain = new ArrayList<>();
        Object identity = raw.get("identity");
        if (identity instanceof Map<?, ?> map && map.get("chain") instanceof List<?> list) {
            chain.addAll(list);
        } else if (raw.get("chain") instanceof List<?> list) {
            chain.addAll(list);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chain", chain);
        return body;
    }

    private static Map<String, Object> bounds(Map<String, Object> raw) {
        Object box = raw.get("bounds");
        Map<String, Object> bounds = new LinkedHashMap<>();
        if (box instanceof Map<?, ?> map) {
            bounds.put("x", number(map.get("x")));
            bounds.put("y", number(map.get("y")));
            bounds.put("width", number(map.get("width")));
            bounds.put("height", number(map.get("height")));
            return bounds;
        }
        bounds.put("x", number(raw.get("x")));
        bounds.put("y", number(raw.get("y")));
        bounds.put("width", number(raw.get("width")));
        bounds.put("height", number(raw.get("height")));
        return bounds;
    }

    private static Object backend(Map<String, Object> raw) {
        Object value = first(raw, "backendNodeId", "backend_node_id");
        if (value == null && raw.get("handle") instanceof Number) value = raw.get("handle");
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static String frame(Map<String, Object> raw) {
        String frame = text(first(raw, "frame", "frame_id", "frameId"));
        return frame.isBlank() ? "top" : frame;
    }

    private static Map<String, Object> extra(Map<String, Object> raw, String fields) {
        Set<String> wanted = fieldNames(fields);
        Map<String, Object> extra = new LinkedHashMap<>();
        if (wanted.isEmpty()) return extra;
        for (String name : wanted) {
            if (CANONICAL.contains(name) || ROW.contains(name) || "bounds".equals(name) || "identity".equals(name)) continue;
            if (!raw.containsKey(name)) continue;
            extra.put(name, raw.get(name));
        }
        return extra;
    }

    static Set<String> fieldNames(String fields) {
        Set<String> names = new LinkedHashSet<>();
        if (fields == null || fields.isBlank()) return names;
        for (String part : fields.split(",")) {
            String token = part.trim();
            if (!token.isEmpty()) names.add(token);
        }
        return names;
    }

    private static Object first(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            if (raw.containsKey(key) && raw.get(key) != null) return raw.get(key);
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int number(Object value) {
        if (value instanceof Number number) return (int) Math.round(number.doubleValue());
        if (value == null) return 0;
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

}
