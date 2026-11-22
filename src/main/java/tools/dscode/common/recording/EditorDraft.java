package tools.dscode.common.recording;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The in-page step editor's list. Until someone edits, the list is the live
 * generated script. After an edit, that list is the source of truth: text,
 * order, and nesting stay, deleted generated steps stay deleted, and a new
 * interaction appends.
 */
public final class EditorDraft {
    private EditorDraft() {
    }

    public record Row(String id, String text, int depth, String key, boolean custom) {
        Row withText(String next) {
            return new Row(id, next, depth, key, true);
        }

        Row withDepth(int next) {
            return new Row(id, text, next, key, custom);
        }
    }

    public static boolean edited(Map<String, Object> state) {
        if (state == null) return false;
        Object value = state.get("edited");
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    public static List<Row> rows(StepGenerator.Script generated, Map<String, Object> state) {
        List<Row> live = fromGenerated(generated);
        if (!edited(state)) return live;
        return merge(live, stored(state), removed(state));
    }

    public static List<String> phrases(List<Row> rows) {
        List<String> phrases = new ArrayList<>();
        if (rows == null) return phrases;
        for (Row row : rows) phrases.add(row.text() == null ? "" : row.text());
        return phrases;
    }

    public static List<Integer> depths(List<Row> rows) {
        List<Integer> depths = new ArrayList<>();
        if (rows == null) return depths;
        for (Row row : rows) depths.add(Math.max(0, row.depth()));
        return depths;
    }

    public static List<Map<String, Object>> maps(List<Row> rows) {
        List<Map<String, Object>> maps = new ArrayList<>();
        if (rows == null) return maps;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", row.id());
            map.put("index", index);
            map.put("text", row.text());
            map.put("depth", Math.max(0, row.depth()));
            maps.add(map);
        }
        return maps;
    }

    /** Gherkin for the editor. A deeper following row makes this row a parent (`:`). */
    public static String gherkin(List<Row> rows) {
        StringBuilder text = new StringBuilder();
        if (rows == null) return "";
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) text.append('\n');
            text.append(line(rows, index));
        }
        return text.toString();
    }

    /** One feature-file line, already indented for a step body. */
    public static String featureLine(List<String> steps, List<Integer> depths, int index) {
        List<Row> rows = new ArrayList<>();
        for (int cursor = 0; cursor < steps.size(); cursor++) {
            int depth = depths != null && cursor < depths.size() ? depths.get(cursor) : 0;
            rows.add(new Row("s" + (cursor + 1), steps.get(cursor), depth, "g:#" + cursor, false));
        }
        return "    " + line(rows, index);
    }

    public static void apply(Map<String, Object> state, StepGenerator.Script generated, String action, Map<String, String> args) {
        if (state == null) throw new IllegalArgumentException("record editor has no session");
        List<Row> rows = new ArrayList<>(rows(generated, state));
        List<String> gone = removed(state);
        String act = action == null ? "" : action.toLowerCase(Locale.ROOT);
        Map<String, String> flags = args == null ? Map.of() : args;
        switch (act) {
            case "edit" -> edit(rows, number(flags.get("index"), "record --edit needs --index"), flags.get("text"));
            case "move" -> move(rows, number(flags.get("from"), "record --move needs --from"), number(flags.get("to"), "record --move needs --to"), flags.get("depth"));
            case "nest" -> indent(rows, number(flags.get("index"), "record --nest needs --index"), 1);
            case "outdent" -> indent(rows, number(flags.get("index"), "record --outdent needs --index"), -1);
            case "add" -> add(state, rows, flags);
            case "delete" -> delete(rows, gone, number(flags.get("index"), "record --delete needs --index"));
            default -> throw new IllegalArgumentException("record editor action is edit, move, nest, outdent, add, or delete");
        }
        normalize(rows);
        write(state, rows, gone);
    }

    public static void install(Map<String, Object> state, List<String> steps, List<Integer> depths) {
        if (state == null) return;
        List<Row> rows = new ArrayList<>();
        List<String> phrases = steps == null ? List.of() : steps;
        for (int index = 0; index < phrases.size(); index++) {
            int depth = depths != null && index < depths.size() ? depths.get(index) : 0;
            String id = "s" + (index + 1);
            rows.add(new Row(id, phrases.get(index), Math.max(0, depth), "manual:" + id, true));
        }
        normalize(rows);
        state.put("removed", new ArrayList<String>());
        write(state, rows, List.of());
    }

    public static String panel(Map<String, Object> state, String name) {
        String panel = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (!"editor".equals(panel) && !"chat".equals(panel)) return "page";
        Object value = panels(state).get(panel);
        return "window".equals(value) ? "window" : "page";
    }

    public static Map<String, String> panels(Map<String, Object> state) {
        Map<String, String> panels = new LinkedHashMap<>();
        panels.put("editor", "page");
        panels.put("chat", "page");
        if (state != null && state.get("panels") instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key == null || value == null) return;
                String name = String.valueOf(key);
                if (!"editor".equals(name) && !"chat".equals(name)) return;
                panels.put(name, "window".equalsIgnoreCase(String.valueOf(value)) ? "window" : "page");
            });
        }
        return panels;
    }

    public static void place(Map<String, Object> state, String name, String where) {
        if (state == null) throw new IllegalArgumentException("record editor has no session");
        String panel = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (!"editor".equals(panel) && !"chat".equals(panel)) {
            throw new IllegalArgumentException("record --panel must be editor or chat");
        }
        String next = "window".equalsIgnoreCase(where) ? "window" : "page";
        Map<String, String> panels = panels(state);
        panels.put(panel, next);
        state.put("panels", panels);
    }

    private static void edit(List<Row> rows, int index, String text) {
        Row row = at(rows, index, "record --edit --index");
        rows.set(index, row.withText(phrase(text)));
    }

    private static void move(List<Row> rows, int from, int to, String depth) {
        Row row = at(rows, from, "record --move --from");
        rows.remove(from);
        int at = to < 0 ? 0 : Math.min(to, rows.size());
        if (depth != null && !depth.isBlank()) row = row.withDepth(RecordingSession.number(depth));
        rows.add(at, row);
    }

    private static void indent(List<Row> rows, int index, int delta) {
        Row row = at(rows, index, delta > 0 ? "record --nest --index" : "record --outdent --index");
        rows.set(index, row.withDepth(row.depth() + delta));
    }

    private static void add(Map<String, Object> state, List<Row> rows, Map<String, String> flags) {
        int at = flags.get("index") == null || flags.get("index").isBlank()
                ? rows.size()
                : RecordingSession.number(flags.get("index"));
        if (at < 0) at = 0;
        if (at > rows.size()) at = rows.size();
        int seq = RecordingSession.number(state.get("manualSeq")) + 1;
        state.put("manualSeq", seq);
        String id = "m" + seq;
        int depth = flags.get("depth") == null || flags.get("depth").isBlank() ? 0 : RecordingSession.number(flags.get("depth"));
        String text = flags.get("text");
        rows.add(at, new Row(id, phrase(text == null || text.isBlank() ? ", " : text), depth, "manual:" + id, true));
    }

    private static void delete(List<Row> rows, List<String> removed, int index) {
        Row row = at(rows, index, "record --delete --index");
        rows.remove(index);
        if (row.key() != null && row.key().startsWith("g:") && !removed.contains(row.key())) removed.add(row.key());
    }

    private static Row at(List<Row> rows, int index, String label) {
        if (index < 0 || index >= rows.size()) {
            throw new IllegalArgumentException(label + " is out of range");
        }
        return rows.get(index);
    }

    private static int number(String raw, String error) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(error);
        return RecordingSession.number(raw);
    }

    static String phrase(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return ", ";
        if (value.startsWith(",") || value.startsWith(":") || value.startsWith("Then ") || value.startsWith("And ")) {
            return value;
        }
        return ", " + value;
    }

    private static void normalize(List<Row> rows) {
        int previous = 0;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            int depth = Math.max(0, row.depth());
            if (index == 0) depth = 0;
            else depth = Math.min(depth, previous + 1);
            if (depth != row.depth()) rows.set(index, new Row(row.id(), row.text(), depth, row.key(), row.custom()));
            previous = depth;
        }
    }

    private static void write(Map<String, Object> state, List<Row> rows, List<String> removed) {
        List<Map<String, Object>> draft = new ArrayList<>();
        for (Row row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", row.id());
            map.put("text", row.text());
            map.put("depth", Math.max(0, row.depth()));
            map.put("key", row.key());
            map.put("custom", row.custom());
            draft.add(map);
        }
        state.put("edited", true);
        state.put("draft", draft);
        state.put("removed", new ArrayList<>(removed));
    }

    private static List<Row> fromGenerated(StepGenerator.Script generated) {
        List<Row> rows = new ArrayList<>();
        if (generated == null || generated.steps() == null) return rows;
        List<String> steps = generated.steps();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int index = 0; index < steps.size(); index++) {
            rows.add(new Row("s" + (index + 1), steps.get(index), 0, key(generated, index, seen), false));
        }
        return rows;
    }

    private static List<Row> merge(List<Row> live, List<Row> draft, List<String> removed) {
        Map<String, Row> byKey = new LinkedHashMap<>();
        for (Row row : live) byKey.put(row.key(), row);
        Set<String> used = new LinkedHashSet<>();
        Set<String> gone = new LinkedHashSet<>(removed);
        List<Row> rows = new ArrayList<>();
        int seq = 0;
        for (Row row : draft) {
            if (row.id() != null && row.id().startsWith("s")) {
                seq = Math.max(seq, RecordingSession.number(row.id().substring(1)));
            }
            if (row.key() != null && row.key().startsWith("manual:")) {
                rows.add(row);
                continue;
            }
            Row fresh = byKey.get(row.key());
            if (fresh == null) {
                if (row.custom()) rows.add(row);
                continue;
            }
            used.add(row.key());
            String text = row.custom() ? row.text() : fresh.text();
            rows.add(new Row(row.id(), text, row.depth(), row.key(), row.custom()));
        }
        for (Row row : live) {
            if (used.contains(row.key()) || gone.contains(row.key())) continue;
            seq++;
            rows.add(new Row("s" + seq, row.text(), 0, row.key(), false));
        }
        return rows;
    }

    private static String key(StepGenerator.Script generated, int index, Map<String, Integer> seen) {
        List<Map<String, String>> kept = generated.kept();
        if (kept != null && index < kept.size() && kept.get(index) != null) {
            Map<String, String> row = kept.get(index);
            String kind = InteractionLog.first(row.get("kind"), row.get("type"), "step");
            String name = InteractionLog.first(row.get("name"), row.get("text"), row.get("target"), "");
            String base = kind + "|" + name;
            int occurrence = seen.merge(base, 1, Integer::sum);
            return "g:" + base + "#" + occurrence;
        }
        return "g:#" + index;
    }

    private static List<Row> stored(Map<String, Object> state) {
        List<Row> rows = new ArrayList<>();
        if (state == null || !(state.get("draft") instanceof List<?> list)) return rows;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Object idValue = map.get("id");
            String id = idValue == null ? "s" + (rows.size() + 1) : String.valueOf(idValue);
            Object textValue = map.get("text");
            String text = textValue == null ? ", " : String.valueOf(textValue);
            int depth = RecordingSession.number(map.get("depth"));
            Object keyValue = map.get("key");
            String key = keyValue == null ? "manual:" + id : String.valueOf(keyValue);
            Object customValue = map.get("custom");
            boolean custom = Boolean.TRUE.equals(customValue) || "true".equalsIgnoreCase(String.valueOf(customValue));
            rows.add(new Row(id, "null".equals(text) ? ", " : text, depth, key, custom));
        }
        return rows;
    }

    private static List<String> removed(Map<String, Object> state) {
        List<String> removed = new ArrayList<>();
        if (state == null || !(state.get("removed") instanceof List<?> list)) return removed;
        for (Object item : list) if (item != null && !"null".equals(String.valueOf(item))) removed.add(String.valueOf(item));
        return removed;
    }

    private static String line(List<Row> rows, int index) {
        Row row = rows.get(index);
        int depth = Math.max(0, row.depth());
        String phrase = row.text() == null ? "" : row.text();
        boolean parent = index + 1 < rows.size() && rows.get(index + 1).depth() > depth;
        String body = (index == 0 ? "Then " : "And ") + phrase;
        if (parent && !phrase.endsWith(":") && !phrase.endsWith("?")) body = body + ":";
        if (depth > 0) body = ":".repeat(depth) + " " + body;
        return body;
    }
}
