package tools.dscode.common.agentfast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code snap --since=<cursor>}: each snap stores its rows under a cursor, and a later snap with that
 * cursor returns only rows that were added, removed, or changed. A row is keyed by role, name,
 * purpose, card, and message id, so a moved short id is not a change.
 */
final class SnapSince {
    private static final int KEEP = 32;
    private static final AtomicLong NEXT = new AtomicLong();
    private static final Map<String, Map<String, String>> SEEN = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) {
            return size() > KEEP;
        }
    };

    private static final List<String> STATE = List.of("visible", "enabled", "value", "checked", "badge",
            "highlight", "guide", "card", "msg");

    private SnapSince() {
    }

    /** Stores {@code rows} and returns the cursor a later {@code --since} names. */
    static String record(String runId, List<Map<String, Object>> rows) {
        String cursor = "s." + (runId == null || runId.isBlank() ? "run" : runId.replaceAll("[^A-Za-z0-9_-]", "")) + "."
                + NEXT.incrementAndGet();
        Map<String, String> picture = picture(rows);
        synchronized (SEEN) {
            SEEN.put(cursor, picture);
        }
        return cursor;
    }

    /** Added, removed, and changed rows since {@code cursor}; null when that cursor is unknown or expired. */
    static Map<String, Object> since(String cursor, List<Map<String, Object>> rows) {
        Map<String, String> before;
        synchronized (SEEN) {
            before = SEEN.get(cursor);
        }
        if (before == null) return null;
        Map<String, String> now = picture(rows);
        Map<String, Map<String, Object>> byKey = keyed(rows);
        List<Object> added = new ArrayList<>();
        List<Object> changed = new ArrayList<>();
        List<Object> removed = new ArrayList<>();
        for (Map.Entry<String, String> entry : now.entrySet()) {
            String old = before.get(entry.getKey());
            if (old == null) added.add(line(byKey.get(entry.getKey()), entry.getValue()));
            else if (!old.equals(entry.getValue())) changed.add(line(byKey.get(entry.getKey()), entry.getValue()));
        }
        for (Map.Entry<String, String> entry : before.entrySet()) {
            if (!now.containsKey(entry.getKey())) removed.add(entry.getValue());
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("since", cursor);
        diff.put("added", added);
        diff.put("removed", removed);
        diff.put("changed", changed);
        return diff;
    }

    private static Object line(Map<String, Object> row, String fallback) {
        if (row == null) return fallback;
        Object id = row.get("id");
        return (id == null || String.valueOf(id).isBlank() ? "" : "id=" + id + " ") + fallback;
    }

    private static Map<String, String> picture(List<Map<String, Object>> rows) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        if (rows == null) return out;
        for (Map<String, Object> row : rows) {
            String key = key(row, counts);
            StringBuilder text = new StringBuilder();
            text.append(row.get("role")).append(" \"").append(row.get("name")).append('"');
            Object purpose = row.get("purpose");
            if (purpose != null) text.append(" purpose=").append(LineView.token(String.valueOf(purpose)));
            for (String state : STATE) {
                Object value = row.get(state);
                if (value != null) text.append(' ').append(state).append('=').append(LineView.token(String.valueOf(value)));
            }
            out.put(key, text.toString());
        }
        return out;
    }

    private static Map<String, Map<String, Object>> keyed(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        if (rows == null) return out;
        for (Map<String, Object> row : rows) out.put(key(row, counts), row);
        return out;
    }

    private static String key(Map<String, Object> row, Map<String, Integer> counts) {
        String card = String.valueOf(row.getOrDefault("card", ""));
        int space = card.indexOf(' ');
        if (space > 0) card = card.substring(0, space);
        String msg = String.valueOf(row.getOrDefault("msg", ""));
        space = msg.indexOf(' ');
        if (space > 0) msg = msg.substring(0, space);
        String base = row.get("role") + "|" + row.get("name") + "|" + row.getOrDefault("purpose", "") + "|" + card + "|" + msg;
        int seen = counts.merge(base, 1, Integer::sum);
        return base + "#" + seen;
    }
}
