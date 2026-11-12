package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.image.TemplateMatch;
import dev.domengine.json.Jsons;
import dev.domengine.tree.DomNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Built-in pipeline engines. Each stage receives the previous nodes and returns the next set. */
public final class Engines {
    private static final ThreadLocal<TemplateMatch.Gray> VIEWPORT = new ThreadLocal<>();

    private Engines() {
    }

    /** Viewport for the next image stage. {@code null} clears it. */
    public static void useViewport(TemplateMatch.Gray gray) {
        if (gray == null) VIEWPORT.remove();
        else VIEWPORT.set(gray);
    }

    public static Query.Hit apply(String engine, String expr, List<DomNode> current, DomNode root, String pierce) {
        return switch (engine) {
            case "css" -> css(expr, scopes(current, root), false);
            case "xpath" -> xpath(expr, scopes(current, root), pierce);
            case "text" -> text(expr, scopes(current, root));
            case "role" -> role(expr, scopes(current, root));
            case "frame" -> frame(expr, current, root);
            case "shadow" -> shadow(expr, current, root);
            case "near" -> near(expr, current, root);
            case "below" -> below(expr, current, root);
            case "desc" -> desc(expr, scopes(current, root));
            case "a11y" -> a11y(expr, scopes(current, root));
            case "image" -> image(expr, scopes(current, root));
            default -> throw new CdpException("invalid engine: " + engine);
        };
    }

    private static List<DomNode> scopes(List<DomNode> current, DomNode root) {
        if (current == null || current.isEmpty()) return List.of(root);
        return current;
    }

    static Query.Hit image(String expr, List<DomNode> scopes) {
        String raw = expr == null ? "" : expr.trim();
        if (raw.toLowerCase(Locale.ROOT).startsWith("ocr")) {
            throw new CdpException("ocr is not available in this build");
        }
        TemplateMatch.Gray viewport = VIEWPORT.get();
        if (viewport == null) throw new CdpException("image engine needs a viewport screenshot");
        TemplateMatch.Gray template = raw.startsWith("P2") ? TemplateMatch.parse(raw) : TemplateMatch.read(Path.of(raw));
        List<DomNode> hits = new ArrayList<>();
        double best = 0;
        for (DomNode scope : scopes) {
            for (DomNode node : lightElements(scope, true)) {
                if (!node.hasBox) continue;
                TemplateMatch.Gray crop = TemplateMatch.crop(
                        viewport,
                        (int) Math.round(node.x),
                        (int) Math.round(node.y),
                        (int) Math.round(node.width),
                        (int) Math.round(node.height)
                );
                double score = TemplateMatch.score(template, crop);
                if (score > best) best = score;
                if (score >= 0.8) hits.add(node);
            }
        }
        ObjectNode image = Jsons.object();
        image.put("confidence", best);
        image.put("template", raw);
        image.put("matched", hits.size());
        return new Query.Hit(Query.distinct(hits), TemplateMatch.why(best, raw), image);
    }

    static Query.Hit css(String expr, List<DomNode> scopes, boolean pierceShadows) {
        List<DomNode> hits = new ArrayList<>();
        for (DomNode scope : scopes) {
            for (DomNode candidate : lightElements(scope, scope.iframe || scope.nodeType == 9 || scope.nodeType == 11 || scope.shadowRoot)) {
                if (matchesSelector(candidate, expr)) hits.add(candidate);
            }
            if (pierceShadows) {
                for (DomNode shadow : collectShadows(scope)) {
                    for (DomNode candidate : lightElements(shadow, true)) {
                        if (matchesSelector(candidate, expr)) hits.add(candidate);
                    }
                }
            }
        }
        List<DomNode> distinct = Query.distinct(hits);
        return new Query.Hit(distinct, "css matched " + distinct.size());
    }

    static Query.Hit xpath(String expr, List<DomNode> scopes, String pierce) {
        String cleaned = expr == null ? "" : expr.trim();
        while (cleaned.startsWith("(") && cleaned.endsWith(")")) cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        boolean intoShadow = pierce != null && (pierce.equals("open") || pierce.equals("all"));
        List<DomNode> hits = new ArrayList<>();
        for (DomNode scope : scopes) {
            List<DomNode> pool = new ArrayList<>(lightElements(scope, true));
            if (intoShadow) {
                for (DomNode shadow : collectShadows(scope)) {
                    if ("open".equals(pierce) && "closed".equalsIgnoreCase(shadow.shadowMode)) continue;
                    pool.addAll(lightElements(shadow, true));
                }
            }
            for (DomNode candidate : pool) {
                if (xpathMatches(candidate, cleaned)) hits.add(candidate);
            }
        }
        List<DomNode> distinct = Query.distinct(hits);
        return new Query.Hit(distinct, "xpath matched " + distinct.size());
    }

    static Query.Hit text(String expr, List<DomNode> scopes) {
        String expected = unquote(expr);
        boolean exact = expr != null && (expr.startsWith("\"") || expr.startsWith("'"));
        List<DomNode> hits = new ArrayList<>();
        for (DomNode scope : scopes) {
            for (DomNode candidate : lightElements(scope, true)) {
                String text = HtmlTree.textOf(candidate);
                if (exact ? text.equals(expected) : text.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT))) {
                    if (!expected.isEmpty()) hits.add(candidate);
                }
            }
        }
        return new Query.Hit(Query.distinct(hits), "text matched " + hits.size());
    }

    static Query.Hit role(String expr, List<DomNode> scopes) {
        String role = expr;
        String name = null;
        int bracket = expr.indexOf('[');
        if (bracket >= 0) {
            role = expr.substring(0, bracket).trim();
            String inside = expr.substring(bracket + 1, expr.endsWith("]") ? expr.length() - 1 : expr.length());
            if (inside.startsWith("name=")) name = unquote(inside.substring(5));
        }
        List<DomNode> hits = new ArrayList<>();
        for (DomNode scope : scopes) {
            for (DomNode candidate : lightElements(scope, true)) {
                if (!role.equalsIgnoreCase(Query.roleOf(candidate))) continue;
                if (name != null) {
                    String accessible = candidate.accessibleName.isBlank() ? HtmlTree.textOf(candidate) : candidate.accessibleName;
                    if (!accessible.equalsIgnoreCase(name) && !accessible.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) continue;
                }
                hits.add(candidate);
            }
        }
        return new Query.Hit(Query.distinct(hits), "role matched " + hits.size());
    }

    static Query.Hit frame(String expr, List<DomNode> current, DomNode root) {
        List<DomNode> scopes = current == null || current.isEmpty() || current.get(0) == root ? List.of(root) : current;
        List<DomNode> frames = new ArrayList<>();
        for (DomNode scope : scopes) {
            for (DomNode candidate : lightElements(scope, true)) {
                if (!candidate.iframe) continue;
                if (expr.isBlank() || candidate.attributes.getOrDefault("id", "").equals(expr)
                        || candidate.attributes.getOrDefault("name", "").equals(expr)
                        || matchesSelector(candidate, expr.startsWith("#") || expr.startsWith(".") || expr.contains("[") ? expr : "#" + expr)
                        || matchesSelector(candidate, expr)) {
                    frames.add(candidate);
                }
            }
        }
        if (frames.isEmpty()) {
            for (DomNode candidate : lightElements(root, true)) {
                if (candidate.iframe && (expr.equals(candidate.frameId) || expr.equals(candidate.attributes.get("id")))) frames.add(candidate);
            }
        }
        return new Query.Hit(Query.distinct(frames), "frame entered " + frames.size());
    }

    static Query.Hit shadow(String expr, List<DomNode> current, DomNode root) {
        List<DomNode> considered = new ArrayList<>();
        for (DomNode scope : scopes(current, root)) {
            if (scope.nodeType == 1) considered.add(scope);
            considered.addAll(lightElements(scope, true));
        }
        String selector = expr == null || expr.isBlank() ? "*" : expr;
        List<DomNode> roots = new ArrayList<>();
        for (DomNode host : Query.distinct(considered)) {
            if (host.shadowRoots.isEmpty()) continue;
            if ("*".equals(selector) || matchesSelector(host, selector)) roots.addAll(host.shadowRoots);
        }
        return new Query.Hit(roots, "shadow entered " + roots.size());
    }

    static Query.Hit near(String expr, List<DomNode> anchors, DomNode root) {
        double limit = 150;
        String selector = expr;
        if (expr != null && expr.matches("\\d+(\\.\\d+)?")) {
            limit = Double.parseDouble(expr);
            selector = "*";
        }
        List<DomNode> candidates = css(selector, List.of(root), false).nodes;
        List<DomNode> hits = new ArrayList<>();
        for (DomNode candidate : candidates) {
            if (anchors.contains(candidate)) continue;
            for (DomNode anchor : anchors) {
                if (!candidate.hasBox || !anchor.hasBox) continue;
                if (distance(candidate, anchor) <= limit) {
                    hits.add(candidate);
                    break;
                }
            }
        }
        hits.sort((a, b) -> Double.compare(minDistance(a, anchors), minDistance(b, anchors)));
        return new Query.Hit(Query.distinct(hits), "near matched " + hits.size());
    }

    static Query.Hit below(String expr, List<DomNode> anchors, DomNode root) {
        List<DomNode> candidates = css(expr, List.of(root), false).nodes;
        List<DomNode> hits = new ArrayList<>();
        for (DomNode candidate : candidates) {
            for (DomNode anchor : anchors) {
                if (!candidate.hasBox || !anchor.hasBox) continue;
                if (candidate.y >= anchor.y + anchor.height) {
                    hits.add(candidate);
                    break;
                }
            }
        }
        hits.sort((a, b) -> Double.compare(a.y, b.y));
        return new Query.Hit(Query.distinct(hits), "below matched " + hits.size());
    }

    static Query.Hit desc(String expr, List<DomNode> scopes) {
        JsonNode description;
        try {
            description = Jsons.MAPPER.readTree(expr);
        } catch (Exception failure) {
            throw new CdpException("invalid description");
        }
        List<DomNode> hits = new ArrayList<>();
        for (DomNode scope : scopes) {
            for (DomNode candidate : lightElements(scope, true)) {
                if (descriptionMatches(candidate, description)) hits.add(candidate);
            }
        }
        return new Query.Hit(Query.distinct(hits), "desc matched " + hits.size());
    }

    static Query.Hit a11y(String expr, List<DomNode> scopes) {
        if (expr == null || expr.isBlank()) throw new CdpException("invalid a11y expression");
        String[] groups = expr.split("\\|");
        List<DomNode> hits = new ArrayList<>();
        for (DomNode scope : scopes) {
            for (DomNode candidate : lightElements(scope, true)) {
                if (hidden(candidate) && !expr.toLowerCase(Locale.ROOT).contains("hidden")) continue;
                for (String group : groups) {
                    if (group.isBlank()) continue;
                    if (predicates(candidate, group)) {
                        hits.add(candidate);
                        break;
                    }
                }
            }
        }
        return new Query.Hit(Query.distinct(hits), "a11y matched " + hits.size() + " (" + expr + ")");
    }

    private static boolean predicates(DomNode node, String group) {
        for (String raw : group.split(",")) {
            String predicate = raw.trim();
            if (predicate.isEmpty() || "includehidden".equalsIgnoreCase(predicate)) continue;
            if (!onePredicate(node, predicate)) return false;
        }
        return true;
    }

    private static boolean onePredicate(DomNode node, String predicate) {
        String op = operator(predicate);
        String key;
        String value = "";
        if (op.isEmpty()) key = predicate;
        else {
            int at = predicate.indexOf(op);
            key = predicate.substring(0, at).trim();
            value = unquote(predicate.substring(at + op.length()).trim());
        }
        String actual = field(node, key);
        if (op.isEmpty()) return !actual.isBlank() && !"false".equalsIgnoreCase(actual);
        return compare(actual, op, value);
    }

    private static String operator(String predicate) {
        for (String op : List.of("!=", ">=", "<=", "~=", "^=", "$=", ">", "<", "=")) {
            if (predicate.contains(op)) return op;
        }
        return "";
    }

    private static boolean compare(String actual, String op, String expected) {
        return switch (op) {
            case "=" -> actual.equals(expected);
            case "!=" -> !actual.equals(expected);
            case "~=" -> actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
            case "^=" -> actual.startsWith(expected);
            case "$=" -> actual.endsWith(expected);
            case ">", "<", ">=", "<=" -> compareNumber(actual, op, expected);
            default -> false;
        };
    }

    private static boolean compareNumber(String actual, String op, String expected) {
        try {
            int left = Integer.parseInt(actual.trim());
            int right = Integer.parseInt(expected.trim());
            return switch (op) {
                case ">" -> left > right;
                case "<" -> left < right;
                case ">=" -> left >= right;
                case "<=" -> left <= right;
                default -> false;
            };
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    private static String field(DomNode node, String key) {
        String name = key.toLowerCase(Locale.ROOT);
        return switch (name) {
            case "role" -> Query.roleOf(node);
            case "name" -> node.accessibleName.isBlank() ? HtmlTree.textOf(node) : node.accessibleName;
            case "text" -> HtmlTree.textOf(node);
            case "tag" -> node.localName;
            case "value" -> node.attributes.getOrDefault("value", node.nodeValue == null ? "" : node.nodeValue);
            case "disabled" -> node.attributes.containsKey("disabled") ? "true" : "false";
            case "hidden" -> hidden(node) ? "true" : "false";
            default -> node.attributes.getOrDefault(name, "");
        };
    }

    private static boolean hidden(DomNode node) {
        return !node.displayed || "true".equals(node.attributes.get("aria-hidden"));
    }

    private static boolean descriptionMatches(DomNode node, JsonNode description) {
        if (description.has("tag") && !description.get("tag").asText("").equalsIgnoreCase(node.localName)) return false;
        if (description.has("role") && !description.get("role").asText("").equalsIgnoreCase(Query.roleOf(node))) return false;
        if (description.has("text") && !HtmlTree.textOf(node).toLowerCase(Locale.ROOT).contains(description.get("text").asText("").toLowerCase(Locale.ROOT))) return false;
        if (description.has("name")) {
            String name = node.accessibleName.isBlank() ? HtmlTree.textOf(node) : node.accessibleName;
            if (!name.toLowerCase(Locale.ROOT).contains(description.get("name").asText("").toLowerCase(Locale.ROOT))) return false;
        }
        JsonNode attr = description.get("attr");
        if (attr != null && attr.isObject()) {
            for (var entry : attr.properties()) {
                if (!entry.getValue().asText("").equals(node.attributes.get(entry.getKey()))) return false;
            }
        }
        return true;
    }

    private static boolean xpathMatches(DomNode node, String expr) {
        if (expr.startsWith("//")) {
            String rest = expr.substring(2);
            String tag = "*";
            String predicate = "";
            int bracket = rest.indexOf('[');
            if (bracket >= 0) {
                tag = rest.substring(0, bracket);
                predicate = rest.substring(bracket + 1, rest.endsWith("]") ? rest.length() - 1 : rest.length());
            } else tag = rest;
            if (!tag.equals("*") && !tag.equalsIgnoreCase(node.localName) && !tag.equalsIgnoreCase(node.nodeName)) return false;
            if (predicate.isEmpty()) return true;
            return xpathPredicate(node, predicate);
        }
        if (expr.startsWith("/")) {
            List<String> steps = new ArrayList<>();
            for (String step : expr.split("/")) if (!step.isBlank()) steps.add(step);
            return absolute(node, steps);
        }
        return false;
    }

    private static boolean absolute(DomNode node, List<String> steps) {
        List<DomNode> chain = new ArrayList<>();
        DomNode cursor = node;
        while (cursor != null && cursor.nodeType == 1) {
            chain.add(0, cursor);
            cursor = cursor.parent;
        }
        if (chain.size() < steps.size()) return false;
        for (int i = 0; i < steps.size(); i++) {
            String step = steps.get(i);
            String tag = step;
            int bracket = step.indexOf('[');
            if (bracket >= 0) tag = step.substring(0, bracket);
            DomNode at = chain.get(i);
            if (!tag.equals("*") && !tag.equalsIgnoreCase(at.localName)) return false;
        }
        return chain.size() == steps.size();
    }

    private static boolean xpathPredicate(DomNode node, String predicate) {
        String trimmed = predicate.trim();
        if (trimmed.startsWith("@")) {
            int eq = trimmed.indexOf('=');
            if (eq < 0) return node.attributes.containsKey(trimmed.substring(1));
            String attr = trimmed.substring(1, eq).trim();
            String value = unquote(trimmed.substring(eq + 1).trim());
            return value.equals(node.attributes.get(attr));
        }
        if (trimmed.startsWith("contains(")) {
            int comma = trimmed.indexOf(',');
            if (comma < 0) return false;
            String value = unquote(trimmed.substring(comma + 1, trimmed.endsWith(")") ? trimmed.length() - 1 : trimmed.length()).trim());
            return HtmlTree.textOf(node).toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
        }
        return false;
    }

    static boolean matchesSelector(DomNode node, String selector) {
        if (selector == null || selector.isBlank() || "*".equals(selector)) return node.nodeType == 1;
        for (String group : splitTop(selector, ',')) {
            if (matchesChain(node, group.trim())) return true;
        }
        return false;
    }

    private static boolean matchesChain(DomNode node, String selector) {
        List<String> compounds = new ArrayList<>();
        List<Character> combinators = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < selector.length(); i++) {
            char c = selector.charAt(i);
            if (c == '>' || c == ' ') {
                if (current.isEmpty()) continue;
                compounds.add(current.toString().trim());
                current.setLength(0);
                combinators.add(c == '>' ? '>' : ' ');
                while (i + 1 < selector.length() && selector.charAt(i + 1) == ' ') i++;
            } else current.append(c);
        }
        if (!current.isEmpty()) compounds.add(current.toString().trim());
        if (compounds.isEmpty()) return false;
        return matchFrom(node, compounds, combinators, compounds.size() - 1);
    }

    private static boolean matchFrom(DomNode node, List<String> compounds, List<Character> combinators, int index) {
        if (node == null || node.nodeType != 1) return false;
        if (!matchesCompound(node, compounds.get(index))) return false;
        if (index == 0) return true;
        char combinator = combinators.get(index - 1);
        if (combinator == '>') return matchFrom(elementParent(node), compounds, combinators, index - 1);
        DomNode parent = elementParent(node);
        while (parent != null) {
            if (matchFrom(parent, compounds, combinators, index - 1)) return true;
            parent = elementParent(parent);
        }
        return false;
    }

    private static DomNode elementParent(DomNode node) {
        DomNode parent = node.parent;
        while (parent != null && parent.nodeType != 1) parent = parent.parent;
        if (parent != null && parent.iframe) return null;
        return parent;
    }

    private static boolean matchesCompound(DomNode node, String compound) {
        if (compound.isEmpty() || "*".equals(compound)) return true;
        int i = 0;
        boolean saw = false;
        while (i < compound.length()) {
            char c = compound.charAt(i);
            if (c == '#') {
                int end = nextStop(compound, i + 1);
                if (!compound.substring(i + 1, end).equals(node.attributes.get("id"))) return false;
                i = end;
                saw = true;
            } else if (c == '.') {
                int end = nextStop(compound, i + 1);
                if (!hasClass(node, compound.substring(i + 1, end))) return false;
                i = end;
                saw = true;
            } else if (c == '[') {
                int end = compound.indexOf(']', i);
                if (end < 0) return false;
                if (!matchAttr(node, compound.substring(i + 1, end))) return false;
                i = end + 1;
                saw = true;
            } else {
                int end = nextStop(compound, i);
                String tag = compound.substring(i, end);
                if (!tag.isEmpty() && !tag.equalsIgnoreCase(node.localName) && !tag.equals("*")) return false;
                i = end;
                saw = true;
            }
        }
        return saw;
    }

    private static int nextStop(String text, int start) {
        int i = start;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '#' || c == '.' || c == '[') break;
            i++;
        }
        return i;
    }

    private static boolean matchAttr(DomNode node, String body) {
        String op = "=";
        for (String candidate : List.of("*=", "^=", "$=", "=")) {
            if (body.contains(candidate)) {
                op = candidate;
                break;
            }
        }
        if (!body.contains(op)) return node.attributes.containsKey(body.trim());
        int at = body.indexOf(op);
        String name = body.substring(0, at).trim();
        String value = unquote(body.substring(at + op.length()).trim());
        String actual = node.attributes.get(name);
        if (actual == null) return false;
        return switch (op) {
            case "*=" -> actual.contains(value);
            case "^=" -> actual.startsWith(value);
            case "$=" -> actual.endsWith(value);
            default -> actual.equals(value);
        };
    }

    private static boolean hasClass(DomNode node, String className) {
        String raw = node.attributes.get("class");
        if (raw == null) return false;
        for (String part : raw.split("\\s+")) if (part.equals(className)) return true;
        return false;
    }

    static List<DomNode> lightElements(DomNode scope, boolean descend) {
        List<DomNode> into = new ArrayList<>();
        collectLight(scope, into, descend || scope.nodeType == 9 || scope.nodeType == 11 || scope.shadowRoot);
        return into;
    }

    private static void collectLight(DomNode scope, List<DomNode> into, boolean descend) {
        for (DomNode child : scope.children) {
            if (child.nodeType != 1 || child.shadowRoot) continue;
            into.add(child);
            if (descend && !child.iframe) collectLight(child, into, true);
            else if (scope.iframe && descend) collectLight(child, into, true);
        }
    }

    private static List<DomNode> collectShadows(DomNode scope) {
        List<DomNode> shadows = new ArrayList<>();
        gatherShadows(scope, shadows);
        return shadows;
    }

    private static void gatherShadows(DomNode node, List<DomNode> into) {
        into.addAll(node.shadowRoots);
        for (DomNode child : node.children) {
            if (!child.iframe) gatherShadows(child, into);
        }
    }

    private static List<String> splitTop(String text, char comma) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') depth++;
            else if (c == ']' && depth > 0) depth--;
            if (c == comma && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else current.append(c);
        }
        if (!current.isEmpty()) parts.add(current.toString());
        return parts;
    }

    static String unquote(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    private static double distance(DomNode a, DomNode b) {
        double ax = a.x + a.width / 2;
        double ay = a.y + a.height / 2;
        double bx = b.x + b.width / 2;
        double by = b.y + b.height / 2;
        return Math.hypot(ax - bx, ay - by);
    }

    private static double minDistance(DomNode node, List<DomNode> anchors) {
        double best = Double.MAX_VALUE;
        for (DomNode anchor : anchors) {
            if (anchor.hasBox && node.hasBox) best = Math.min(best, distance(node, anchor));
        }
        return best;
    }
}
