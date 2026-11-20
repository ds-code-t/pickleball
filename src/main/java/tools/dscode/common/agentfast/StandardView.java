package tools.dscode.common.agentfast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared read view for snap rows and for map lists on chat, overlay, and events.
 * Priority is human, answer, click, mutation, agent, system. Grouping is a
 * {@code # type} or {@code # role} header when {@code --group} asks for it.
 * Own agent echoes drop only when {@code --echo=hide}. Element identity fields
 * stay on a projected row. Text is trimmed and zero-width characters are removed.
 */
public final class StandardView {
    private static final List<String> IDENTITY = List.of(
            "ref", "id", "role", "name", "visible", "enabled", "interactive", "frame"
    );
    private static final List<String> VIEW_KEYS = List.of(
            "format", "fields", "where", "limit", "budget", "group", "priority", "echo"
    );

    private StandardView() {
    }

    public static boolean requested(Map<String, String> args) {
        if (args == null) return false;
        for (String key : VIEW_KEYS) {
            String value = args.get(key);
            if (value != null && !value.isBlank()) return true;
        }
        return false;
    }

    /** Trim, drop zero-width characters, and collapse whitespace. */
    public static String normalize(String value) {
        if (value == null) return "";
        StringBuilder text = new StringBuilder();
        boolean space = false;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '\u200b' || ch == '\u200c' || ch == '\u200d' || ch == '\ufeff') continue;
            if (Character.isWhitespace(ch)) {
                if (!text.isEmpty()) space = true;
                continue;
            }
            if (space) {
                text.append(' ');
                space = false;
            }
            text.append(ch);
        }
        return text.toString();
    }

    /** Lower numbers are kept first when a reply is over budget. */
    public static int priority(Map<String, ?> row) {
        String source = text(row, "source");
        String type = text(row, "type");
        String event = text(row, "event");
        if ("answer".equalsIgnoreCase(type) || "answer".equalsIgnoreCase(event)) return 1;
        if ("click".equalsIgnoreCase(type) || "click".equalsIgnoreCase(event)) return 2;
        if ("mutation".equalsIgnoreCase(type) || "mutation".equalsIgnoreCase(event)) return 3;
        if ("agent".equalsIgnoreCase(source)) return 4;
        if ("system".equalsIgnoreCase(source)) return 5;
        if ("human".equalsIgnoreCase(source) || "human".equalsIgnoreCase(type) || "chat".equalsIgnoreCase(type)) return 0;
        return 6;
    }

    public static Map<String, Object> applyBody(Map<String, Object> body, Map<String, String> args) {
        if (body == null || !requested(args)) return body;
        Map<String, Object> copy = new LinkedHashMap<>(body);
        for (String key : List.of("messages", "events", "lines", "hits", "ops")) {
            Object value = copy.get(key);
            if (!(value instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map)) continue;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) rows.add(stringMap(map));
            }
            List<Map<String, Object>> shaped = shape(rows, args);
            copy.put(key, shaped);
            if ("messages".equals(key) || "events".equals(key)) copy.put("count", shaped.size());
        }
        return copy;
    }

    public static List<Map<String, Object>> shape(List<Map<String, Object>> rows, Map<String, String> args) {
        List<Map<String, Object>> copy = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                if (row != null) copy.add(normalizeRow(row));
            }
        }
        if (hideEcho(args)) {
            copy.removeIf(row -> "agent".equalsIgnoreCase(text(row, "source")));
        }
        List<Map<String, Object>> filtered = SnapView.filter(copy, args);
        if (rank(args)) {
            List<Map<String, Object>> ranked = new ArrayList<>(filtered);
            ranked.sort(Comparator.comparingInt(StandardView::priority));
            filtered = ranked;
        }
        int budget = SnapView.byteBudget(args);
        filtered = fit(filtered, budget);
        if (grouped(args)) return groupedRows(filtered, args.get("group"));
        return filtered;
    }

    private static boolean hideEcho(Map<String, String> args) {
        if (args == null) return false;
        String echo = args.get("echo");
        return echo != null && (echo.isBlank() || "hide".equalsIgnoreCase(echo) || "false".equalsIgnoreCase(echo));
    }

    private static boolean rank(Map<String, String> args) {
        if (args == null) return false;
        String priority = args.get("priority");
        return priority != null && !priority.isBlank() && !"off".equalsIgnoreCase(priority);
    }

    private static boolean grouped(Map<String, String> args) {
        if (args == null) return false;
        String group = args.get("group");
        return "type".equalsIgnoreCase(group) || "role".equalsIgnoreCase(group);
    }

    private static List<Map<String, Object>> fit(List<Map<String, Object>> rows, int budget) {
        if (rows == null || rows.isEmpty() || budget <= 0) return rows == null ? List.of() : rows;
        List<Map<String, Object>> copy = new ArrayList<>(rows);
        while (copy.size() > 1 && bytes(copy) > budget) copy.remove(copy.size() - 1);
        return copy;
    }

    private static int bytes(List<Map<String, Object>> rows) {
        int total = 0;
        for (Map<String, Object> row : rows) total += String.valueOf(row).length() + 1;
        return total;
    }

    private static List<Map<String, Object>> groupedRows(List<Map<String, Object>> rows, String group) {
        String key = "role".equalsIgnoreCase(group) ? "role" : "type";
        List<Map<String, Object>> grouped = new ArrayList<>();
        String current = null;
        for (Map<String, Object> row : rows) {
            String name = text(row, key);
            if (name.isBlank()) name = text(row, "event");
            if (name.isBlank()) name = "item";
            if (!name.equals(current)) {
                current = name;
                Map<String, Object> header = new LinkedHashMap<>();
                header.put("group", name);
                header.put("line", "# " + name);
                grouped.add(header);
            }
            grouped.add(row);
        }
        return grouped;
    }

    private static Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (value instanceof String text) copy.put(key, normalize(text));
            else copy.put(key, value);
        });
        return copy;
    }

    private static Map<String, Object> stringMap(Map<?, ?> row) {
        Map<String, Object> copy = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (key != null) copy.put(String.valueOf(key), value);
        });
        return copy;
    }

    private static String text(Map<String, ?> row, String key) {
        if (row == null || key == null) return "";
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** Fields a compact element row keeps even when {@code --fields} names a subset. */
    public static List<String> elementFields() {
        return IDENTITY;
    }

    public static String priorityName(int rank) {
        return switch (rank) {
            case 0 -> "human";
            case 1 -> "answer";
            case 2 -> "click";
            case 3 -> "mutation";
            case 4 -> "agent";
            case 5 -> "system";
            default -> "other";
        };
    }

    public static String priorityName(Map<String, ?> row) {
        return priorityName(priority(row));
    }

    static String blank(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
