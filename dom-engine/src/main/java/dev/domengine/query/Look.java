package dev.domengine.query;

import dev.domengine.cdp.CdpException;
import dev.domengine.log.EngineLog;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computed-style stage. A comma is AND and a pipe is OR, both only outside quotes and parentheses.
 * Offline HTML uses the style attribute and CSS inheritance. A stored computed map wins when present.
 */
final class Look {
    private static final Pattern PREDICATE = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9-]*)\\s*(!=|>=|<=|~=|\\^=|\\$=|[><]=?|=)\\s*(.*)$",
            Pattern.DOTALL);
    private static final Set<String> INHERITED = Set.of(
            "color", "cursor", "direction", "font-family", "font-size", "font-stretch", "font-style",
            "font-variant", "font-weight", "letter-spacing", "line-height", "text-align", "text-indent",
            "text-transform", "visibility", "white-space", "word-spacing"
    );
    private static final Map<String, Rgba> NAMED = namedColors();

    private Look() {
    }

    static Query.Hit apply(String expr, List<DomNode> scopes) {
        if (expr == null || expr.isBlank()) throw new CdpException("invalid look expression");
        List<String> groups = splitDepth(expr, '|');
        if (groups.stream().allMatch(String::isBlank)) throw new CdpException("invalid look expression");
        List<DomNode> hits = new ArrayList<>();
        for (DomNode scope : scopes) {
            for (DomNode candidate : Engines.lightElements(scope, true)) {
                List<String> matched = firstMatch(candidate, groups);
                if (matched == null) continue;
                hits.add(candidate);
                EngineLog.info("look", candidate.id() + " " + String.join(" ", matched));
            }
        }
        List<DomNode> distinct = Query.distinct(hits);
        return new Query.Hit(distinct, "look matched " + distinct.size() + " (" + expr + ")");
    }

    private static List<String> firstMatch(DomNode node, List<String> groups) {
        for (String group : groups) {
            if (group.isBlank()) continue;
            List<String> matched = matchGroup(node, group);
            if (matched != null) return matched;
        }
        return null;
    }

    private static List<String> matchGroup(DomNode node, String group) {
        List<String> matched = new ArrayList<>();
        for (String raw : splitDepth(group, ',')) {
            String predicate = raw.trim();
            if (predicate.isEmpty()) continue;
            String shown = one(node, predicate);
            if (shown == null) return null;
            matched.add(shown);
        }
        return matched.isEmpty() ? null : matched;
    }

    /** {@code null} when the predicate fails. Otherwise {@code prop=shown}. */
    private static String one(DomNode node, String predicate) {
        Matcher matcher = PREDICATE.matcher(predicate);
        String key;
        String op = "";
        String expected = "";
        if (matcher.matches()) {
            key = matcher.group(1).toLowerCase(Locale.ROOT);
            op = matcher.group(2) == null ? "" : matcher.group(2);
            expected = unquote(matcher.group(3));
        } else if (predicate.matches("[A-Za-z][A-Za-z0-9-]*")) {
            key = predicate.toLowerCase(Locale.ROOT);
        } else {
            throw new CdpException("invalid look expression");
        }
        String actual = resolve(node, key);
        if (op.isEmpty()) {
            return actual.isBlank() ? null : key + "=" + shown(key, actual);
        }
        return compare(key, actual, op, expected) ? key + "=" + shown(key, actual) : null;
    }

    private static String resolve(DomNode node, String key) {
        if ("contrast".equals(key)) return contrast(node);
        if (!node.computed.isEmpty()) return node.computed.getOrDefault(key, "");
        String direct = declared(node).get(key);
        if (direct != null) return direct;
        if (!INHERITED.contains(key)) return "";
        DomNode parent = node.parent;
        while (parent != null) {
            if (!parent.computed.isEmpty()) return parent.computed.getOrDefault(key, "");
            String inherited = declared(parent).get(key);
            if (inherited != null) return inherited;
            parent = parent.parent;
        }
        return "";
    }

    private static String contrast(DomNode node) {
        Rgba fg = parseColor(resolvePaint(node, "color", true));
        Rgba bg = paintedBackground(node);
        if (fg == null || bg == null) return "";
        double lighter = Math.max(luminance(fg), luminance(bg));
        double darker = Math.min(luminance(fg), luminance(bg));
        return Double.toString((lighter + 0.05) / (darker + 0.05));
    }

    /** Foreground may inherit. Background does not, and a missing canvas is white. */
    private static Rgba paintedBackground(DomNode node) {
        for (DomNode cursor = node; cursor != null; cursor = cursor.parent) {
            String raw = resolvePaint(cursor, "background-color", false);
            Rgba color = parseColor(raw);
            if (color != null && color.a > 0.01) return color;
        }
        return new Rgba(255, 255, 255, 1);
    }

    private static String resolvePaint(DomNode node, String key, boolean inherit) {
        if (!node.computed.isEmpty()) {
            String value = node.computed.getOrDefault(key, "");
            if (!value.isBlank() || !inherit) return value;
        } else {
            String direct = declared(node).get(key);
            if (direct != null) return direct;
        }
        if (!inherit) return "";
        return node.parent == null ? "" : resolve(node.parent, key);
    }

    private static boolean compare(String key, String actual, String op, String expected) {
        return switch (op) {
            case "=" -> same(key, actual, expected);
            case "!=" -> !same(key, actual, expected);
            case "~=" -> actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
            case "^=" -> actual.startsWith(expected);
            case "$=" -> actual.endsWith(expected);
            case ">", "<", ">=", "<=" -> compareNumber(key, actual, op, expected);
            default -> false;
        };
    }

    private static boolean same(String key, String actual, String expected) {
        Rgba left = parseColor(actual);
        Rgba right = parseColor(expected);
        if (left != null && right != null) return left.same(right);
        Double ln = number(key, actual);
        Double rn = number(key, expected);
        if (ln != null && rn != null) return Math.abs(ln - rn) < 1e-6;
        return actual.trim().equalsIgnoreCase(expected.trim());
    }

    private static boolean compareNumber(String key, String actual, String op, String expected) {
        Double left = number(key, actual);
        Double right = number(key, expected);
        if (left == null || right == null) return false;
        return switch (op) {
            case ">" -> left > right;
            case "<" -> left < right;
            case ">=" -> left >= right;
            case "<=" -> left <= right;
            default -> false;
        };
    }

    private static Double number(String key, String raw) {
        if (raw == null) return null;
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if ("font-weight".equals(key)) {
            if ("normal".equals(text)) return 400d;
            if ("bold".equals(text)) return 700d;
        }
        if (text.isEmpty()) return null;
        int i = 0;
        if (text.charAt(0) == '+' || text.charAt(0) == '-') i++;
        boolean digit = false;
        boolean dot = false;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
                continue;
            }
            if (c == '.' && !dot) {
                dot = true;
                continue;
            }
            break;
        }
        if (!digit) return null;
        String rest = text.substring(i).trim();
        if (!rest.isEmpty() && !rest.chars().allMatch(ch -> Character.isLetter(ch) || ch == '%')) return null;
        try {
            return Double.parseDouble(text.substring(0, i));
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static String shown(String key, String actual) {
        if ("contrast".equals(key)) {
            Double value = number(key, actual);
            return value == null ? actual : String.format(Locale.ROOT, "%.2f", value);
        }
        Rgba color = parseColor(actual);
        if (color == null) return actual;
        if (color.a >= 0.999) return String.format("#%02x%02x%02x", color.r, color.g, color.b);
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.2f)", color.r, color.g, color.b, color.a);
    }

    static Map<String, String> declared(DomNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null) return out;
        String style = node.attributes.get("style");
        if (style == null || style.isBlank()) return out;
        for (String part : splitDepth(style, ';')) {
            String piece = part.trim();
            if (piece.isEmpty()) continue;
            int colon = indexAtDepth(piece, ':');
            if (colon <= 0) continue;
            String name = piece.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = piece.substring(colon + 1).replace("!important", "").trim();
            if (!name.isEmpty()) out.put(name, value);
        }
        return out;
    }

    private static List<String> splitDepth(String text, char separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quote = false;
        char quoteChar = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote) {
                current.append(c);
                if (c == quoteChar) quote = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = true;
                quoteChar = c;
                current.append(c);
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')' && depth > 0) depth--;
            if (c == separator && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    private static int indexAtDepth(String text, char wanted) {
        int depth = 0;
        boolean quote = false;
        char quoteChar = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote) {
                if (c == quoteChar) quote = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = true;
                quoteChar = c;
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')' && depth > 0) depth--;
            else if (c == wanted && depth == 0) return i;
        }
        return -1;
    }

    private static String unquote(String raw) {
        return Engines.unquote(raw == null ? "" : raw.trim());
    }

    static Rgba parseColor(String raw) {
        if (raw == null) return null;
        String text = raw.trim().toLowerCase(Locale.ROOT).replace("!important", "").trim();
        if (text.isEmpty()) return null;
        Rgba named = NAMED.get(text);
        if (named != null) return named;
        if (text.startsWith("#")) return hex(text);
        if (text.startsWith("rgb")) return rgb(text);
        return null;
    }

    private static Rgba hex(String text) {
        try {
            String hex = text.substring(1);
            if (hex.length() == 3 || hex.length() == 4) {
                char r = hex.charAt(0);
                char g = hex.charAt(1);
                char b = hex.charAt(2);
                double a = hex.length() == 4 ? hexByte(String.valueOf(hex.charAt(3)) + hex.charAt(3)) / 255d : 1;
                return new Rgba(hexByte("" + r + r), hexByte("" + g + g), hexByte("" + b + b), a);
            }
            if (hex.length() == 6 || hex.length() == 8) {
                double a = hex.length() == 8 ? hexByte(hex.substring(6, 8)) / 255d : 1;
                return new Rgba(hexByte(hex.substring(0, 2)), hexByte(hex.substring(2, 4)), hexByte(hex.substring(4, 6)), a);
            }
            return null;
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static int hexByte(String text) {
        return Integer.parseInt(text, 16);
    }

    private static Rgba rgb(String text) {
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open < 0 || close < open) return null;
        String body = text.substring(open + 1, close).trim();
        List<String> parts = body.indexOf(',') >= 0 ? splitDepth(body, ',') : List.of(body.split("\\s+"));
        List<String> channels = new ArrayList<>();
        for (String part : parts) {
            String piece = part.trim();
            if (piece.isEmpty()) continue;
            int slash = piece.indexOf('/');
            if (slash >= 0) {
                channels.add(piece.substring(0, slash).trim());
                channels.add(piece.substring(slash + 1).trim());
            } else {
                channels.add(piece);
            }
        }
        if (channels.size() < 3) return null;
        Integer r = channel(channels.get(0));
        Integer g = channel(channels.get(1));
        Integer b = channel(channels.get(2));
        if (r == null || g == null || b == null) return null;
        double a = 1;
        if (channels.size() >= 4) {
            Double alpha = alpha(channels.get(3));
            if (alpha == null) return null;
            a = alpha;
        }
        return new Rgba(r, g, b, a);
    }

    private static Integer channel(String raw) {
        String text = raw.trim();
        try {
            if (text.endsWith("%")) return clamp((int) Math.round(Double.parseDouble(text.substring(0, text.length() - 1).trim()) * 2.55));
            return clamp((int) Math.round(Double.parseDouble(text)));
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static Double alpha(String raw) {
        String text = raw.trim();
        try {
            if (text.endsWith("%")) return Math.min(1, Math.max(0, Double.parseDouble(text.substring(0, text.length() - 1).trim()) / 100d));
            return Math.min(1, Math.max(0, Double.parseDouble(text)));
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static int clamp(int value) {
        return Math.min(255, Math.max(0, value));
    }

    private static double luminance(Rgba color) {
        return 0.2126 * channel(color.r) + 0.7152 * channel(color.g) + 0.0722 * channel(color.b);
    }

    private static double channel(int value) {
        double c = value / 255d;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static Map<String, Rgba> namedColors() {
        Map<String, Rgba> colors = new LinkedHashMap<>();
        colors.put("black", new Rgba(0, 0, 0, 1));
        colors.put("silver", new Rgba(192, 192, 192, 1));
        colors.put("gray", new Rgba(128, 128, 128, 1));
        colors.put("grey", new Rgba(128, 128, 128, 1));
        colors.put("white", new Rgba(255, 255, 255, 1));
        colors.put("maroon", new Rgba(128, 0, 0, 1));
        colors.put("red", new Rgba(255, 0, 0, 1));
        colors.put("purple", new Rgba(128, 0, 128, 1));
        colors.put("fuchsia", new Rgba(255, 0, 255, 1));
        colors.put("green", new Rgba(0, 128, 0, 1));
        colors.put("lime", new Rgba(0, 255, 0, 1));
        colors.put("olive", new Rgba(128, 128, 0, 1));
        colors.put("yellow", new Rgba(255, 255, 0, 1));
        colors.put("navy", new Rgba(0, 0, 128, 1));
        colors.put("blue", new Rgba(0, 0, 255, 1));
        colors.put("teal", new Rgba(0, 128, 128, 1));
        colors.put("aqua", new Rgba(0, 255, 255, 1));
        colors.put("orange", new Rgba(255, 165, 0, 1));
        colors.put("transparent", new Rgba(0, 0, 0, 0));
        return Map.copyOf(colors);
    }

    record Rgba(int r, int g, int b, double a) {
        boolean same(Rgba other) {
            return r == other.r && g == other.g && b == other.b && Math.abs(a - other.a) < 0.01;
        }
    }
}
