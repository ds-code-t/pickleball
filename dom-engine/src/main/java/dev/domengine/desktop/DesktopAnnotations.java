package dev.domengine.desktop;

import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Extra STATE and REL lines that {@code NativeDesktop.parseLines} ignores. */
final class DesktopAnnotations {
    private DesktopAnnotations() {
    }

    static void apply(DomNode root, String output, String idAttribute) {
        if (root == null || output == null || output.isBlank()) return;
        Map<String, DomNode> byId = new LinkedHashMap<>();
        index(root, idAttribute, byId);
        for (String line : output.split("\\R")) {
            if (line.startsWith("STATE\t")) {
                String[] parts = line.split("\t", 3);
                if (parts.length < 3) continue;
                DomNode node = byId.get(parts[1]);
                if (node == null) continue;
                for (String state : parts[2].split(",")) add(node, state.trim());
            } else if (line.startsWith("REL\t")) {
                String[] parts = line.split("\t", 4);
                if (parts.length < 4) continue;
                DomNode node = byId.get(parts[1]);
                if (node == null) continue;
                String kind = parts[2].trim().toLowerCase(Locale.ROOT);
                if ("labeled-by".equals(kind) || "label-for".equals(kind)) node.attributes.put(kind, parts[3].trim());
            }
        }
    }

    static void applyAtspiStates(DomNode node, String output) {
        List<Long> words = ints(output);
        if (node == null || words.isEmpty()) return;
        long low = words.get(0);
        if (!bit(low, 8)) add(node, "disabled");
        if (bit(low, 4)) add(node, "checked");
        if (bit(low, 10)) add(node, "expanded");
        if (bit(low, 12)) add(node, "focused");
        if (!bit(low, 25) && !bit(low, 30)) add(node, "offscreen");
    }

    static void applyAtspiRelations(DomNode node, String output) {
        if (node == null || output == null) return;
        String[] tokens = output.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if ((!"uint32".equals(tokens[i]) && !"int32".equals(tokens[i])) || i + 1 >= tokens.length) continue;
            long type;
            try {
                type = Long.parseLong(tokens[i + 1]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (type != 1 && type != 2) continue;
            for (int j = i + 2; j < tokens.length; j++) {
                if (!"path".equals(tokens[j]) || j + 1 >= tokens.length) continue;
                String path = tokens[j + 1].replace("\"", "");
                if (type == 1) node.attributes.put("label-for", path);
                else node.attributes.put("labeled-by", path);
                break;
            }
        }
    }

    private static void index(DomNode node, String idAttribute, Map<String, DomNode> byId) {
        if (node == null) return;
        String id = node.attributes.get(idAttribute);
        if (id != null && !id.isBlank()) byId.putIfAbsent(id, node);
        for (DomNode child : node.children) index(child, idAttribute, byId);
    }

    private static void add(DomNode node, String state) {
        if (state == null || state.isBlank() || node.states.contains(state)) return;
        if (!List.of("disabled", "focused", "checked", "expanded", "offscreen").contains(state)) return;
        node.states.add(state);
        if ("offscreen".equals(state)) node.displayed = false;
    }

    private static boolean bit(long word, int index) {
        return ((word >>> index) & 1L) == 1L;
    }

    private static List<Long> ints(String output) {
        List<Long> values = new ArrayList<>();
        if (output == null) return values;
        String[] tokens = output.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if (!"uint32".equals(tokens[i]) && !"int32".equals(tokens[i])) continue;
            if (i + 1 >= tokens.length) continue;
            try {
                values.add(Long.parseLong(tokens[i + 1]));
            } catch (NumberFormatException ignored) {
                // Skip a non-numeric token.
            }
        }
        return values;
    }
}
