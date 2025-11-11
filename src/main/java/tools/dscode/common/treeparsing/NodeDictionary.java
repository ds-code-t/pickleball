package tools.dscode.common.treeparsing;

import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal dictionary: field names → ParseNode instances; YAML rows → 2D children (row0 parsing, rows≥1 phases). */
public abstract class NodeDictionary {

    /** Build a fresh top-level ParseNode from a tiny YAML grid spec (deep-copying referenced fields). */
    public ParseNode buildFromYaml(String yaml) {
        Objects.requireNonNull(yaml, "yaml");
        Spec spec = parseTinyYaml(yaml);
        Map<String, ParseNode> pool = reflectNodePool();

        List<List<ParseNode>> rows = new ArrayList<>();
        for (List<String> rowIds : spec.rows) {
            List<ParseNode> row = new ArrayList<>(rowIds.size());
            for (String id : rowIds) {
                ParseNode original = pool.get(id);
                if (original == null) {
                    throw new IllegalArgumentException("Unknown node id '" + id + "' on " + getClass().getSimpleName());
                }
                row.add(deepCopyTree(original)); // fresh identity per placement
            }
            rows.add(List.copyOf(row));
        }
        return new ParseNode(spec.rootKey, "", List.copyOf(rows), null) {};
    }

    /* ---------------------------- reflection of fields ---------------------------- */

    private Map<String, ParseNode> reflectNodePool() {
        Map<String, ParseNode> map = new LinkedHashMap<>();
        Class<?> c = this.getClass();
        while (c != null && NodeDictionary.class.isAssignableFrom(c)) {
            for (Field f : c.getDeclaredFields()) {
                if (!ParseNode.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                try {
                    Object v = f.get(this);
                    if (v != null) map.put(f.getName(), (ParseNode) v);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
            c = c.getSuperclass();
        }
        return map;
    }

    /* ---------------------------- deep copy via delegation ---------------------------- */

    private static ParseNode deepCopyTree(ParseNode src) {
        List<List<ParseNode>> newRows = new ArrayList<>(src.rows.size());
        for (List<ParseNode> row : src.rows) {
            List<ParseNode> newRow = new ArrayList<>(row.size());
            for (ParseNode child : row) newRow.add(deepCopyTree(child));
            newRows.add(List.copyOf(newRow));
        }
        return new DelegatingNode(src, List.copyOf(newRows));
    }

    private static final class DelegatingNode extends ParseNode {
        private final ParseNode delegate;
        DelegatingNode(ParseNode delegate, List<List<ParseNode>> rows) {
            super(delegate.keyName, delegate.keySuffix, rows, delegate.selfRegex);
            this.delegate = delegate;
        }
        @Override public void beforeCapture(MatchNode self) { delegate.beforeCapture(self); }
        @Override public String onCapture(String s) { return delegate.onCapture(s); }
        @Override public void afterCapture(MatchNode self) { delegate.afterCapture(self); }
        @Override public MatchNode createMatchNode(ParseNode node, MatchNode parent, String text, String token,
                                                   com.google.common.collect.LinkedListMultimap<String,Object> gs) {
            return delegate.createMatchNode(this, parent, text, token, gs);
        }
        @Override public String getUniqueKey(int n) { return delegate.getUniqueKey(n); }
    }

    /* ---------------------------- tiny YAML: Root: - [a,b] - [c] ---------------------------- */

    private static final class Spec {
        final String rootKey; final List<List<String>> rows;
        Spec(String rootKey, List<List<String>> rows) { this.rootKey = rootKey; this.rows = rows; }
    }

    private static Spec parseTinyYaml(String yaml) {
        String[] lines = yaml.replace("\r\n","\n").split("\n");
        int i = 0; while (i < lines.length && lines[i].trim().isBlank()) i++;
        if (i >= lines.length) throw new IllegalArgumentException("Empty YAML");
        String head = lines[i].trim();
        int colon = head.indexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("Expected 'RootKey:' on first non-blank line");
        String rootKey = head.substring(0, colon).trim();
        i++;

        Pattern rowPat = Pattern.compile("^\\s*-\\s*\\[(.*)]\\s*$");
        List<List<String>> rows = new ArrayList<>();
        for (; i < lines.length; i++) {
            String raw = lines[i];
            if (raw.trim().isBlank()) continue;
            Matcher m = rowPat.matcher(raw);
            if (!m.find()) throw new IllegalArgumentException("Expected '- [a, b, c]': " + raw);
            String inside = m.group(1);
            if (inside.isBlank()) { rows.add(List.of()); continue; }
            String[] parts = inside.split(",");
            List<String> ids = new ArrayList<>();
            for (String p : parts) {
                String id = p.trim();
                if (!id.isEmpty()) ids.add(id);
            }
            rows.add(List.copyOf(ids));
        }
        if (rows.isEmpty()) rows = List.of(List.of());
        return new Spec(rootKey, List.copyOf(rows));
    }
}
