package dev.domengine.affinity;

import dev.domengine.log.EngineLog;
import dev.domengine.premap.Platform;
import dev.domengine.premap.SectionDetector;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import dev.domengine.tree.DomNode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Pure label-to-control scorer.
 * The parameter is {@code List<?>} until G2 Phase 1 lands {@code dev.domengine.query.RelationTerm}.
 * A {@code RelationTerm} record (accessors {@code relation}, {@code negated}, {@code anchor}, {@code weight})
 * is read without this package declaring that type. Maps with the same keys are accepted.
 */
public final class AffinityScorer {
    private static final Map<DomNode, Map<DomNode, Integer>> ORDER = CollectionsOrder();

    private AffinityScorer() {
    }

    public static AffinityScore score(DomNode label, DomNode interactable, List<?> relations) {
        return score(label, interactable, relations, AffinityWeights.fromEnv());
    }

    public static double scoreValue(DomNode label, DomNode interactable, List<?> relations) {
        return score(label, interactable, relations).total();
    }

    public static AffinityScore score(DomNode label, DomNode interactable, List<?> relations, AffinityWeights weights) {
        if (explicit(label, interactable)) {
            Map<String, Double> parts = new LinkedHashMap<>();
            parts.put("explicit", 1.0);
            List<String> reasons = new ArrayList<>();
            reasons.add("explicit");
            double total = 1.0;
            total = applyRelations(label, interactable, relations, parts, reasons, total);
            return new AffinityScore(clamp(total), parts, reasons);
        }
        AffinityWeights w = (weights == null ? AffinityWeights.defaults() : weights).normalized();
        double proximity = proximity(label, interactable);
        double alignment = alignment(label, interactable);
        double role = role(label, interactable);
        double text = text(label, interactable);
        double section = section(label, interactable);
        Map<String, Double> parts = new LinkedHashMap<>();
        parts.put("proximity", w.proximity() * proximity);
        parts.put("alignment", w.alignment() * alignment);
        parts.put("role", w.role() * role);
        parts.put("text", w.text() * text);
        parts.put("section", w.section() * section);
        List<String> reasons = new ArrayList<>();
        if (proximity > 0) reasons.add("proximity");
        if (alignment > 0) reasons.add("alignment");
        if (role > 0) reasons.add("role");
        if (text > 0) reasons.add("text");
        if (section > 0) reasons.add("section");
        double total = 0;
        for (double value : parts.values()) total += value;
        total = applyRelations(label, interactable, relations, parts, reasons, total);
        return new AffinityScore(clamp(total), parts, reasons);
    }

    public static double scoreValue(DomNode label, DomNode interactable, List<?> relations, AffinityWeights weights) {
        return score(label, interactable, relations, weights).total();
    }

    /** Greedy one-to-one binding. Legends, group labels, and column headers may bind to many controls. */
    public static Bindings bind(DomNode root) {
        return bind(root, AffinityWeights.fromEnv(), Platform.BROWSER);
    }

    public static Bindings bind(DomNode root, AffinityWeights weights, Platform platform) {
        if (root != null) {
            root.linkParents();
            SectionDetector.detect(root, platform == null ? Platform.BROWSER : platform);
        }
        List<DomNode> labels = new ArrayList<>();
        List<DomNode> controls = new ArrayList<>();
        walk(root, labels, controls);
        List<Scored> pairs = new ArrayList<>();
        for (DomNode label : labels) {
            for (DomNode control : controls) {
                if (label == control) continue;
                AffinityScore scored = score(label, control, List.of(), weights);
                if (scored.total() < 0.35) continue;
                pairs.add(new Scored(label, control, scored, order(label), order(control)));
            }
        }
        pairs.sort((left, right) -> {
            int byBand = Integer.compare(band(right.score.total()), band(left.score.total()));
            if (byBand != 0) return byBand;
            int byLabel = Integer.compare(left.labelOrder, right.labelOrder);
            if (byLabel != 0) return byLabel;
            return Integer.compare(left.controlOrder, right.controlOrder);
        });
        List<Binding> bindings = new ArrayList<>();
        Map<DomNode, Boolean> usedControls = new IdentityHashMap<>();
        Map<DomNode, Boolean> usedLabels = new IdentityHashMap<>();
        for (Scored pair : pairs) {
            if (usedControls.containsKey(pair.control)) continue;
            boolean shared = shared(pair.label, pair.control);
            if (!shared && usedLabels.containsKey(pair.label)) continue;
            bindings.add(new Binding(pair.label, pair.control, pair.score));
            usedControls.put(pair.control, Boolean.TRUE);
            if (!shared) usedLabels.put(pair.label, Boolean.TRUE);
        }
        List<DomNode> unlabeled = new ArrayList<>();
        for (DomNode control : controls) {
            if (!usedControls.containsKey(control)) unlabeled.add(control);
        }
        EngineLog.info("affinity", "affinity bind labels=" + bindings.size() + " unlabeled=" + unlabeled.size());
        return new Bindings(bindings, unlabeled);
    }

    public record Binding(DomNode label, DomNode target, AffinityScore score) {
    }

    public record Bindings(List<Binding> labels, List<DomNode> unlabeled) {
        public Binding forControl(String id) {
            for (Binding binding : labels) {
                if (id.equals(binding.target.attributes.get("id")) || id.equals(binding.target.id())) return binding;
            }
            return null;
        }
    }

    private static int band(double score) {
        return (int) Math.floor((score / 0.02) + 1e-9);
    }

    private record Scored(DomNode label, DomNode control, AffinityScore score, int labelOrder, int controlOrder) {
    }

    private static double applyRelations(
            DomNode label, DomNode interactable, List<?> relations,
            Map<String, Double> parts, List<String> reasons, double total
    ) {
        if (relations == null || relations.isEmpty()) return total;
        double adjustment = 0;
        for (Object raw : relations) {
            Term term = readTerm(raw);
            if (term == null || term.relation.isBlank()) continue;
            if (!satisfied(label, interactable, term)) continue;
            if (term.negated) {
                adjustment -= 0.50 * term.weight;
                reasons.add("negated " + term.relation.toLowerCase(Locale.ROOT) + " satisfied");
            } else {
                adjustment += 0.10 * term.weight;
                reasons.add(term.relation.toLowerCase(Locale.ROOT) + " satisfied");
            }
        }
        if (adjustment != 0) parts.put("relation", adjustment);
        return total + adjustment;
    }

    private static boolean explicit(DomNode label, DomNode control) {
        if (label == null || control == null) return false;
        String forId = attr(label, "for");
        String controlId = attr(control, "id");
        if (!forId.isBlank() && forId.equals(controlId)) return true;
        String labelledBy = attr(control, "aria-labelledby");
        String labelId = attr(label, "id");
        if (!labelId.isBlank() && containsToken(labelledBy, labelId)) return true;
        if ("label".equals(label.localName) && label.containsNode(control)) return true;
        if ("legend".equals(label.localName)) {
            DomNode fieldset = ancestorTag(label, "fieldset");
            if (fieldset != null && (fieldset == control || fieldset.containsNode(control))) return true;
        }
        return desktopExplicit(label, control);
    }

    private static boolean desktopExplicit(DomNode label, DomNode control) {
        String labeledBy = first(attr(control, "labeled-by"), attr(control, "uia-labeled-by"), attr(control, "ax-title"));
        String labelFor = first(attr(label, "label-for"), attr(label, "uia-label-for"));
        if (!labeledBy.isBlank() && matchesHandle(label, labeledBy)) return true;
        if (!labelFor.isBlank() && matchesHandle(control, labelFor)) return true;
        return false;
    }

    private static boolean matchesHandle(DomNode node, String token) {
        if (node == null || token == null || token.isBlank()) return false;
        if (token.equals(node.id())) return true;
        if (token.equals(attr(node, "id"))) return true;
        if (token.equals(attr(node, "uia-runtime-id"))) return true;
        if (token.equals(attr(node, "ax-path"))) return true;
        if (token.equals(attr(node, "atspi-path"))) return true;
        return false;
    }

    private static double proximity(DomNode label, DomNode control) {
        Rect left = rect(label);
        Rect right = rect(control);
        if (!left.present || !right.present) return 0;
        double d0 = Math.max(48.0, 3.0 * Math.max(1.0, left.height));
        return Math.exp(-gap(left, right) / d0);
    }

    private static double alignment(DomNode label, DomNode control) {
        Rect left = rect(label);
        Rect right = rect(control);
        if (!left.present || !right.present) {
            return order(label) > order(control) ? 0.1 : 0;
        }
        String role = Query.roleOf(control);
        boolean check = "checkbox".equals(role) || "radio".equals(role);
        if (check && left.x >= right.x + right.width - 1 && verticalOverlap(left, right) >= 0.5) return 1.0;
        if (left.x + left.width <= right.x + 1 && verticalOverlap(left, right) >= 0.5) return 1.0;
        if (left.y + left.height <= right.y + 1 && Math.abs(left.x - right.x) <= 16) return 0.9;
        if (left.y >= right.y + right.height - 1) return 0.2;
        return 0;
    }

    private static double role(DomNode label, DomNode control) {
        if (hasOwnName(control)) return 0.2;
        if (labelLike(label) && wantsLabel(control)) return 1.0;
        return 0;
    }

    private static double text(DomNode label, DomNode control) {
        String left = norm(textOf(label));
        if (left.isBlank()) return 0;
        double best = 0;
        for (String field : fields(control)) {
            String right = norm(field);
            if (right.isBlank()) continue;
            best = Math.max(best, Math.max(jaccard(left, right), levenshtein(left, right)));
        }
        return best;
    }

    static double section(DomNode label, DomNode control) {
        List<String> left = path(label == null ? "" : label.sectionPath);
        List<String> right = path(control == null ? "" : control.sectionPath);
        if (left.equals(right)) return 1.0;
        if (parent(left).equals(parent(right))) return 0.5;
        if (!top(left).equals(top(right))) return 0;
        return 0.5;
    }

    private static boolean satisfied(DomNode label, DomNode control, Term term) {
        String name = term.relation.toLowerCase(Locale.ROOT).replace('-', '_');
        boolean positive = switch (name) {
            case "inside" -> inside(control, term.anchor);
            case "near" -> near(label, control, term.anchor);
            case "above" -> placed("above", control, anchorNode(label, control, term.anchor));
            case "below" -> placed("below", control, anchorNode(label, control, term.anchor));
            case "left_of" -> placed("left-of", control, anchorNode(label, control, term.anchor));
            case "right_of" -> placed("right-of", control, anchorNode(label, control, term.anchor));
            case "before" -> order(control) < order(anchorNode(label, control, term.anchor));
            case "after" -> order(control) > order(anchorNode(label, control, term.anchor));
            default -> false;
        };
        return term.negated ? !positive : positive;
    }

    private static boolean inside(DomNode node, String anchor) {
        if (node == null) return false;
        if (anchor == null || anchor.isBlank()) return false;
        DomNode cursor = node;
        while (cursor != null) {
            if (anchor.equalsIgnoreCase(Query.roleOf(cursor)) || anchor.equals(cursor.attributes.get("id"))
                    || anchor.equalsIgnoreCase(cursor.accessibleName) || anchor.equalsIgnoreCase(cursor.localName)) {
                if (cursor != node) return true;
            }
            cursor = cursor.parent;
        }
        return false;
    }

    private static boolean near(DomNode label, DomNode control, String anchor) {
        DomNode other = anchorNode(label, control, anchor);
        Rect a = rect(control);
        Rect b = rect(other);
        if (!a.present || !b.present) return false;
        return gap(a, b) <= 150;
    }

    private static boolean placed(String name, DomNode node, DomNode anchor) {
        Rect candidate = rect(node);
        Rect other = rect(anchor);
        if (!candidate.present || !other.present || node == anchor) return false;
        return switch (name) {
            case "above" -> candidate.y + candidate.height <= other.y + 1;
            case "below" -> candidate.y >= other.y + other.height - 1;
            case "left-of" -> candidate.x + candidate.width <= other.x + 1;
            case "right-of" -> candidate.x >= other.x + other.width - 1;
            default -> false;
        };
    }

    private static DomNode anchorNode(DomNode label, DomNode control, String anchor) {
        if (anchor == null || anchor.isBlank()) return label;
        DomNode root = control != null ? top(control) : top(label);
        DomNode found = findAnchor(root, anchor);
        return found == null ? label : found;
    }

    private static DomNode findAnchor(DomNode node, String anchor) {
        if (node == null) return null;
        if (anchor.equalsIgnoreCase(Query.roleOf(node)) || anchor.equals(node.attributes.get("id"))
                || anchor.equalsIgnoreCase(node.accessibleName)) return node;
        for (DomNode child : node.children) {
            DomNode found = findAnchor(child, anchor);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = findAnchor(shadow, anchor);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean hasOwnName(DomNode control) {
        if (control == null) return false;
        String role = Query.roleOf(control);
        String text = HtmlTree.textOf(control);
        if (("button".equals(role) || "link".equals(role)) && !text.isBlank()) return true;
        if (("button".equals(role) || "link".equals(role)) && !control.accessibleName.isBlank()) return true;
        return false;
    }

    private static boolean wantsLabel(DomNode control) {
        return switch (Query.roleOf(control)) {
            case "textbox", "combobox", "checkbox", "radio", "slider", "spinbutton", "listbox", "switch", "button", "searchbox" -> true;
            default -> false;
        };
    }

    static boolean labelLike(DomNode node) {
        if (node == null) return false;
        if (node.nodeType == 3) return !textOf(node).isBlank();
        String tag = node.localName == null ? "" : node.localName;
        if (tag.equals("label") || tag.equals("legend") || tag.equals("dt") || tag.equals("th")) return true;
        if (tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6') return true;
        String role = Query.roleOf(node);
        return role.equals("text") || role.equals("static") || role.equals("label") || role.equals("heading");
    }

    public static boolean interactable(DomNode node) {
        return switch (Query.roleOf(node)) {
            case "button", "textbox", "combobox", "checkbox", "radio", "slider", "spinbutton",
                    "listbox", "switch", "link", "searchbox", "menuitem", "tab" -> true;
            default -> false;
        };
    }

    private static boolean shared(DomNode label, DomNode control) {
        if (label == null) return false;
        if ("legend".equals(label.localName)) return true;
        if ("group".equals(Query.roleOf(label))) return true;
        if ("th".equals(label.localName) || "columnheader".equals(Query.roleOf(label))) {
            return sameColumn(label, control);
        }
        return false;
    }

    private static boolean sameColumn(DomNode header, DomNode control) {
        Rect left = rect(header);
        Rect right = rect(control);
        if (!left.present || !right.present) return false;
        double overlap = Math.min(left.x + left.width, right.x + right.width) - Math.max(left.x, right.x);
        if (overlap <= 0) return false;
        double smaller = Math.min(left.width, right.width);
        return overlap / smaller >= 0.5;
    }

    private static void walk(DomNode node, List<DomNode> labels, List<DomNode> controls) {
        if (node == null) return;
        if (node.nodeType == 1 || node.nodeType == 3) {
            if (labelLike(node)) labels.add(node);
            if (node.nodeType == 1 && interactable(node)) controls.add(node);
        }
        for (DomNode child : node.children) walk(child, labels, controls);
        for (DomNode shadow : node.shadowRoots) walk(shadow, labels, controls);
    }

    private static List<String> fields(DomNode control) {
        List<String> fields = new ArrayList<>();
        if (!control.accessibleName.isBlank()) fields.add(control.accessibleName);
        String text = HtmlTree.textOf(control);
        if (!text.isBlank()) fields.add(text);
        add(fields, attr(control, "id"));
        add(fields, attr(control, "name"));
        add(fields, attr(control, "placeholder"));
        add(fields, attr(control, "title"));
        String described = attr(control, "aria-describedby");
        if (!described.isBlank()) fields.add(resolveIds(control, described));
        return fields;
    }

    private static void add(List<String> fields, String value) {
        if (value != null && !value.isBlank()) fields.add(value);
    }

    private static String resolveIds(DomNode node, String ids) {
        DomNode root = top(node);
        StringBuilder builder = new StringBuilder();
        for (String id : ids.split("\\s+")) {
            DomNode found = findId(root, id);
            if (found == null) continue;
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(textOf(found));
        }
        return builder.toString();
    }

    private static DomNode findId(DomNode node, String id) {
        if (node == null) return null;
        if (id.equals(node.attributes.get("id"))) return node;
        for (DomNode child : node.children) {
            DomNode found = findId(child, id);
            if (found != null) return found;
        }
        return null;
    }

    static String textOf(DomNode node) {
        if (node == null) return "";
        if (node.nodeType == 3) return node.nodeValue == null ? "" : node.nodeValue.trim();
        if (!node.accessibleName.isBlank() && (node.children.isEmpty() || "label".equals(node.role))) return node.accessibleName;
        return HtmlTree.textOf(node);
    }

    private static double jaccard(String left, String right) {
        List<String> a = tokens(left);
        List<String> b = tokens(right);
        if (a.isEmpty() || b.isEmpty()) return 0;
        int inter = 0;
        List<String> unused = new ArrayList<>(b);
        for (String token : a) {
            if (unused.remove(token)) inter++;
        }
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) current.append(Character.toLowerCase(c));
            else if (!current.isEmpty()) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) tokens.add(current.toString());
        return tokens;
    }

    private static double levenshtein(String left, String right) {
        int n = left.length();
        int m = right.length();
        if (n == 0 || m == 0) return 0;
        int[] row = new int[m + 1];
        for (int j = 0; j <= m; j++) row[j] = j;
        for (int i = 1; i <= n; i++) {
            int previous = row[0];
            row[0] = i;
            for (int j = 1; j <= m; j++) {
                int next = row[j];
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1), previous + cost);
                previous = next;
            }
        }
        return 1.0 - ((double) row[m] / Math.max(n, m));
    }

    private static String norm(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private record Rect(double x, double y, double width, double height, boolean present) {
    }

    private static Rect rect(DomNode node) {
        if (node == null) return new Rect(0, 0, 0, 0, false);
        if (node.hasBox && node.width > 0 && node.height > 0) {
            double x = node.hasVisual ? node.visualX : node.x;
            double y = node.hasVisual ? node.visualY : node.y;
            return new Rect(x, y, node.width, node.height, true);
        }
        return new Rect(0, 0, 0, 0, false);
    }

    private static double gap(Rect a, Rect b) {
        double dx = 0;
        if (a.x + a.width < b.x) dx = b.x - (a.x + a.width);
        else if (b.x + b.width < a.x) dx = a.x - (b.x + b.width);
        double dy = 0;
        if (a.y + a.height < b.y) dy = b.y - (a.y + a.height);
        else if (b.y + b.height < a.y) dy = a.y - (b.y + b.height);
        return Math.hypot(dx, dy);
    }

    private static double verticalOverlap(Rect a, Rect b) {
        double overlap = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y);
        if (overlap <= 0) return 0;
        return overlap / Math.min(a.height, b.height);
    }

    private static int order(DomNode node) {
        if (node == null) return -1;
        DomNode root = top(node);
        Map<DomNode, Integer> map = ORDER.computeIfAbsent(root, AffinityScorer::index);
        Integer value = map.get(node);
        return value == null ? map.size() : value;
    }

    private static Map<DomNode, Integer> index(DomNode root) {
        Map<DomNode, Integer> map = new IdentityHashMap<>();
        int[] next = {0};
        stamp(root, map, next);
        return map;
    }

    private static void stamp(DomNode node, Map<DomNode, Integer> map, int[] next) {
        if (node == null) return;
        map.put(node, next[0]++);
        for (DomNode child : node.children) stamp(child, map, next);
        for (DomNode shadow : node.shadowRoots) stamp(shadow, map, next);
    }

    private static DomNode top(DomNode node) {
        DomNode cursor = node;
        while (cursor != null && cursor.parent != null) cursor = cursor.parent;
        return cursor;
    }

    private static DomNode ancestorTag(DomNode node, String tag) {
        DomNode cursor = node == null ? null : node.parent;
        while (cursor != null) {
            if (tag.equals(cursor.localName)) return cursor;
            cursor = cursor.parent;
        }
        return null;
    }

    private static List<String> path(String raw) {
        List<String> parts = new ArrayList<>();
        if (raw == null || raw.isBlank()) return parts;
        for (String part : raw.split("/")) if (!part.isBlank()) parts.add(part);
        return parts;
    }

    private static String parent(List<String> path) {
        if (path.size() <= 1) return String.join("/", path);
        return String.join("/", path.subList(0, path.size() - 1));
    }

    private static String top(List<String> path) {
        if (path.size() >= 2) return path.get(1);
        return path.isEmpty() ? "" : path.get(0);
    }

    private static String attr(DomNode node, String name) {
        if (node == null) return "";
        String value = node.attributes.get(name);
        return value == null ? "" : value;
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static boolean containsToken(String raw, String token) {
        if (raw == null || raw.isBlank() || token == null || token.isBlank()) return false;
        for (String part : raw.split("\\s+")) if (part.equals(token)) return true;
        return false;
    }

    private static double clamp(double value) {
        if (value < 0) return 0;
        if (value > 1) return 1;
        return value;
    }

    private record Term(String relation, boolean negated, String anchor, double weight) {
    }

    private static Term readTerm(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Map<?, ?> map) {
            return new Term(text(map.get("relation")), bool(map.get("negated")), text(map.get("anchor")),
                    map.get("weight") == null ? 1.0 : number(map.get("weight")));
        }
        try {
            return new Term(
                    text(invoke(raw, "relation")),
                    bool(invoke(raw, "negated")),
                    text(invoke(raw, "anchor")),
                    number(invoke(raw, "weight"))
            );
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    private static Object invoke(Object raw, String name) throws ReflectiveOperationException {
        Method method = raw.getClass().getMethod(name);
        return method.invoke(raw);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean flag) return flag;
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return 1.0;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException failure) {
            return 1.0;
        }
    }

    private static Map<DomNode, Map<DomNode, Integer>> CollectionsOrder() {
        return java.util.Collections.synchronizedMap(new WeakHashMap<>());
    }
}
