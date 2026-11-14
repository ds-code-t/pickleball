package tools.dscode.common.composite;

import org.openqa.selenium.By;

import java.util.List;
import java.util.Locale;

/**
 * Dictionary layer of the Control Engine. The query object is one pipeline or
 * expression string, plus the exposure, limit, and explain flags the CLI
 * already accepts. {@code after} and {@code before} are document order, not
 * visual relatives. Workbench, MCP, and the control bridge only forward this object.
 */
public record EngineQuery(
        String text,
        String exposure,
        Integer limit,
        boolean explain,
        boolean promoteVisual,
        boolean geometry,
        boolean interact
) {
    public EngineQuery {
        text = text == null ? "" : text.trim();
        if (exposure != null) {
            exposure = exposure.trim().toLowerCase(Locale.ROOT);
            if (exposure.isEmpty()) exposure = null;
            else if (!"human".equals(exposure) && !"lower".equals(exposure)) {
                throw new IllegalArgumentException("exposure is human or lower");
            }
        }
        if (limit != null && limit < 1) {
            throw new IllegalArgumentException("limit is below 1");
        }
    }

    public static EngineQuery of(String text) {
        return new EngineQuery(text, null, null, false, false, false, false);
    }

    public EngineQuery exposure(String value) {
        return new EngineQuery(text, value, limit, explain, promoteVisual, geometry, interact);
    }

    public EngineQuery limit(Integer value) {
        return new EngineQuery(text, exposure, value, explain, promoteVisual, geometry, interact);
    }

    public EngineQuery explain(boolean value) {
        return new EngineQuery(text, exposure, limit, value, promoteVisual, geometry, interact);
    }

    public EngineQuery promoteVisual(boolean value) {
        return new EngineQuery(text, exposure, limit, explain, value, geometry, interact);
    }

    public EngineQuery geometry(boolean value) {
        return new EngineQuery(text, exposure, limit, explain, promoteVisual, value, interact);
    }

    public EngineQuery interact(boolean value) {
        return new EngineQuery(text, exposure, limit, explain, promoteVisual, geometry, value);
    }

    /** Controller fields. A blank query is category+text, not an engine selector. */
    public static EngineQuery controller(
            String text,
            String exposure,
            Integer limit,
            Boolean explain,
            Boolean promoteVisual
    ) {
        if (text == null || text.isBlank()) return null;
        EngineQuery built = of(text);
        if (exposure != null && !exposure.isBlank()) built = built.exposure(exposure);
        else built = built.exposure("human");
        if (limit != null) built = built.limit(limit);
        if (Boolean.TRUE.equals(explain)) built = built.explain(true);
        if (Boolean.TRUE.equals(promoteVisual)) built = built.promoteVisual(true);
        return built;
    }

    /** Selenium can run a single css= or xpath= stage. Operators and relations need the Control Engine. */
    public By seleniumFallback() {
        if (text == null || text.isBlank()) return null;
        if (text.contains(">>") || relationForm(text) || "expr".equals(dev.domengine.query.Query.leadingEngine(text))) return null;
        if (text.startsWith("css=")) return By.cssSelector(text.substring(4));
        if (text.startsWith("xpath=")) return By.xpath(text.substring(6));
        if (text.startsWith("//") || text.startsWith("(//") || text.startsWith("(/")) return By.xpath(text);
        if (!text.isEmpty() && !text.contains("=")) return By.cssSelector(text);
        return null;
    }

    /**
     * The first visual-relative engine in this selector, or null.
     * {@code after} and {@code before} are document order, not geometry.
     * A filter form {@code name(} counts the same as {@code name=}.
     */
    public static String visualRelative(String selector) {
        if (selector == null || selector.isBlank()) return null;
        boolean quote = false;
        char quoteChar = 0;
        for (int i = 0; i < selector.length(); i++) {
            char c = selector.charAt(i);
            if (quote) {
                if (c == quoteChar) quote = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = true;
                quoteChar = c;
                continue;
            }
            if (i > 0) {
                char before = selector.charAt(i - 1);
                if (Character.isLetterOrDigit(before) || before == '-' || before == '_' || before == '=') continue;
            }
            if (selector.regionMatches(true, i, "not-", 0, 4)) {
                String negated = visualNameAt(selector, i + 4);
                if (negated != null) return canonicalRelative(negated);
            }
            String name = visualNameAt(selector, i);
            if (name != null) return canonicalRelative(name);
        }
        return null;
    }

    private static String visualNameAt(String text, int start) {
        for (String name : List.of("left-of", "right-of", "inside", "above", "below", "near", "left", "right")) {
            if (!text.regionMatches(true, start, name, 0, name.length())) continue;
            int end = start + name.length();
            if (end > text.length()) continue;
            if (end == text.length()) return null;
            char next = text.charAt(end);
            if (next == '=' || next == '(' || next == '@') return name.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static boolean relationForm(String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.trim();
        if (trimmed.regionMatches(true, 0, "not-", 0, 4)) trimmed = trimmed.substring(4).trim();
        return visualNameAt(trimmed, 0) != null
                || documentNameAt(trimmed, 0);
    }

    private static boolean documentNameAt(String text, int start) {
        for (String name : List.of("before", "after")) {
            if (!text.regionMatches(true, start, name, 0, name.length())) continue;
            int end = start + name.length();
            if (end >= text.length()) return false;
            char next = text.charAt(end);
            if (next == '=' || next == '(' || next == '@') return true;
        }
        return false;
    }

    /** Capability name for a visual relative. {@code left} and {@code right} are aliases. */
    public static String canonicalRelative(String name) {
        if ("left".equals(name)) return "left-of";
        if ("right".equals(name)) return "right-of";
        return name;
    }

    private static final java.util.Set<String> VISUAL_RELATIVES = java.util.Set.of(
            "near", "below", "above", "left-of", "right-of", "left", "right", "inside"
    );
}
