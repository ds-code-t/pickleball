package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    private SnapView() {
    }

    public static List<String> fieldNames(Map<String, String> args) {
        return fields(args);
    }

    /** {@code where} and {@code limit} only. Field projection stays with the line printer. */
    public static List<Map<String, Object>> matching(List<Map<String, Object>> rows, Map<String, String> args) {
        List<Map<String, Object>> kept = new ArrayList<>();
        if (rows == null) return kept;
        String where = args == null ? "" : blank(args.get("where"));
        int limit = limit(args);
        for (Map<String, Object> row : rows) {
            if (!matches(row, where)) continue;
            kept.add(row);
            if (limit > 0 && kept.size() >= limit) break;
        }
        return kept;
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
        return copy;
    }

    /**
     * Drops rows from {@code items} until the JSON object is within {@code maxBytes}.
     * The last row is removed too. A single oversized string is clipped. Returns true when anything was removed.
     */
    public static boolean fitJson(Map<String, Object> body, int maxBytes) {
        if (body == null || maxBytes < 1) return false;
        boolean trimmed = false;
        int guard = 0;
        while (jsonBytes(body) > maxBytes && guard++ < 10_000) {
            Object items = body.get("items");
            if (items instanceof List<?> list && !list.isEmpty()) {
                List<Object> copy = new ArrayList<>(list);
                Object last = copy.remove(copy.size() - 1);
                if (copy.isEmpty() && last instanceof String text && text.length() > 32) {
                    int room = Math.max(8, Math.min(text.length() - 1, Math.max(8, maxBytes / 4)));
                    String clip = text.substring(0, Math.min(text.length(), room));
                    if (clip.length() > 3 && clip.length() < text.length()) {
                        clip = clip.substring(0, clip.length() - 3) + "...";
                        copy.add(clip);
                    }
                }
                body.put("items", copy);
                trimmed = true;
                continue;
            }
            if (!clipLongest(body)) break;
            trimmed = true;
        }
        return trimmed;
    }

    /** Shortens the longest string so a tiny budget can still get under the cap. */
    private static boolean clipLongest(Map<String, Object> body) {
        String key = null;
        String best = null;
        Map<String, Object> owner = null;
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (entry.getValue() instanceof String text && text.length() > 24 && (best == null || text.length() > best.length())) {
                key = entry.getKey();
                best = text;
                owner = body;
            } else if (entry.getValue() instanceof Map<?, ?> nested) {
                for (Map.Entry<?, ?> child : nested.entrySet()) {
                    if (child.getKey() instanceof String name && child.getValue() instanceof String text
                            && text.length() > 24 && (best == null || text.length() > best.length())) {
                        key = name;
                        best = text;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) nested;
                        owner = map;
                    }
                }
            }
        }
        if (owner == null || key == null || best == null) return false;
        int room = Math.max(8, best.length() / 2);
        if (room >= best.length()) return false;
        String clip = best.substring(0, room - 3) + "...";
        owner.put(key, clip);
        return true;
    }

    private static int jsonBytes(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8).length;
        } catch (java.io.IOException failure) {
            return Integer.MAX_VALUE;
        }
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
