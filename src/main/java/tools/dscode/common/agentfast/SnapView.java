package tools.dscode.common.agentfast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code where}, {@code fields}, {@code limit}, and {@code budget} for a compact snap. */
public final class SnapView {
    public static final int LINE_CAP = 200;
    public static final int BYTE_CAP = 12_000;
    public static final int REPLY_CAP = 16 * 1024;

    private SnapView() {
    }

    public static List<Map<String, Object>> filter(List<Map<String, Object>> rows, Map<String, String> args) {
        List<Map<String, Object>> kept = new ArrayList<>();
        if (rows == null) return kept;
        String where = args == null ? "" : blank(args.get("where"));
        List<String> fields = fields(args);
        int limit = limit(args);
        for (Map<String, Object> row : rows) {
            if (!matches(row, where)) continue;
            kept.add(fields.isEmpty() ? row : project(row, fields));
            if (limit > 0 && kept.size() >= limit) break;
        }
        return kept;
    }

    public static int lineBudget(Map<String, String> args) {
        int limit = limit(args);
        if (limit > 0) return Math.min(LINE_CAP, limit);
        return LINE_CAP;
    }

    public static int byteBudget(Map<String, String> args) {
        if (args == null) return BYTE_CAP;
        String raw = blank(args.get("budget"));
        if (raw.isEmpty()) return BYTE_CAP;
        try {
            int bytes = Integer.parseInt(raw);
            if (bytes < 1) return BYTE_CAP;
            return Math.min(REPLY_CAP, bytes);
        } catch (NumberFormatException failure) {
            return BYTE_CAP;
        }
    }

    /** Drops lines until the joined text is within the caps. Returns how many were removed. */
    public static int limitLines(List<String> lines, int maxLines, int maxChars, int maxBytes) {
        if (lines == null) return 0;
        int omitted = 0;
        if (maxLines > 0 && lines.size() > maxLines) {
            omitted += lines.size() - maxLines;
            lines.subList(maxLines, lines.size()).clear();
        }
        if (maxChars > 0) {
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line != null && line.length() > maxChars) lines.set(index, line.substring(0, Math.max(0, maxChars - 3)) + "...");
            }
        }
        while (maxBytes > 0 && bytes(lines) > maxBytes && !lines.isEmpty()) {
            lines.remove(lines.size() - 1);
            omitted++;
        }
        return omitted;
    }

    public static int bytes(List<String> lines) {
        int total = 0;
        if (lines == null) return 0;
        for (String line : lines) total += (line == null ? 0 : line.length()) + 1;
        return total;
    }

    private static List<String> fields(Map<String, String> args) {
        if (args == null) return List.of();
        String raw = blank(args.get("fields"));
        if (raw.isEmpty()) return List.of();
        List<String> names = new ArrayList<>();
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    private static int limit(Map<String, String> args) {
        if (args == null) return 0;
        String raw = blank(args.get("limit"));
        if (raw.isEmpty()) return 0;
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

    private static Map<String, Object> project(Map<String, Object> row, List<String> fields) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (String field : fields) {
            if (row.containsKey(field)) copy.put(field, row.get(field));
        }
        if (row.containsKey("ref")) copy.put("ref", row.get("ref"));
        if (row.containsKey("role") && !copy.containsKey("role")) copy.put("role", row.get("role"));
        if (row.containsKey("name") && !copy.containsKey("name")) copy.put("name", row.get("name"));
        return copy;
    }

    private static boolean matches(Map<String, Object> row, String where) {
        if (where.isEmpty() || "before".equals(where) || "after".equals(where)) return true;
        for (String clause : where.split(",")) {
            String text = clause.trim();
            if (text.isEmpty()) continue;
            int tilde = text.indexOf('~');
            int equals = text.indexOf('=');
            boolean contains = tilde >= 0 && (equals < 0 || tilde < equals);
            int cut = contains ? tilde : equals;
            if (cut <= 0) return false;
            String key = text.substring(0, cut).trim();
            String expect = text.substring(cut + 1).trim();
            String actual = String.valueOf(row.get(key));
            if (contains) {
                if (!actual.toLowerCase(Locale.ROOT).contains(expect.toLowerCase(Locale.ROOT))) return false;
            } else if (!actual.equalsIgnoreCase(expect)) {
                return false;
            }
        }
        return true;
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }
}
