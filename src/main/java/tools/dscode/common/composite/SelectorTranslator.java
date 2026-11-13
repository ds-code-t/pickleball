package tools.dscode.common.composite;

import org.openqa.selenium.By;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dictionary layer. Maps a Pickleball element phrase, or a Selenium {@link By}
 * the execution dictionary already built, onto one composite selector.
 * The combined form is one AND group per target and one per context. A phrase
 * that also carries XPath keeps that XPath as the fallback. A pure XPath
 * definition stays XPath and logs one deprecation warning. Pure CSS still
 * passes through and is not deprecated. A space between an XPath function
 * name and {@code (} is removed first. The expression is otherwise unchanged.
 */
public final class SelectorTranslator {
    private static final Pattern FUNCTION_SPACE = Pattern.compile(
            "\\b(not|contains|translate|normalize-space|string-length|count|concat|position|last|"
                    + "substring(?:-before|-after)?|starts-with|boolean|number|string|local-name|"
                    + "namespace-uri|name|id|true|false|floor|ceiling|round|sum|lang)\\s+\\("
    );
    private static final Map<String, String> ROLES = new LinkedHashMap<>();

    static {
        role("button", "button");
        role("submit button", "button");
        role("close button", "button");
        role("link", "link");
        role("textbox", "textbox");
        role("date textbox", "textbox");
        role("textarea", "textbox");
        role("dropdown", "combobox");
        role("option", "option");
        role("radio button", "radio");
        role("checkbox", "checkbox");
        role("toggle", "switch");
        role("text", "text");
        role("icon", "img");
        role("image", "img");
        role("menu", "menu");
        role("menu item", "menuitem");
        role("modal", "dialog");
        role("dialog", "dialog");
        role("tab", "tab");
        role("tab panel", "tabpanel");
        role("section", "region");
        role("question", "region");
        role("table", "table");
        role("row", "row");
        role("cell", "cell");
        role("header", "columnheader");
        role("header row", "row");
        role("column", "columnheader");
        role("field", "textbox");
    }

    private static final Pattern LOOK_CLAUSE = Pattern.compile(
            "(?i)\\bwith\\s+look\\s+([a-z][a-z0-9-]*)\\s+"
                    + "(greater\\s+than|less\\s+than|at\\s+least|at\\s+most|starting\\s+with|ending\\s+with|containing|equaling)\\s+"
                    + "(\"[^\"]*\"|'[^']*'|\\S+)"
    );

    private SelectorTranslator() {
    }

    public static CompositeSelector fromBy(By by) {
        if (by == null) {
            return new CompositeSelector(null, null, null, "web", null, "empty");
        }
        if (by instanceof CombinedBy combined) {
            CompositeSelector selector = new CompositeSelector(
                    executableXPath(combined.xpath()), null, blankToNull(combined.combined()), "web", null, by.toString());
            CompositeLog.debug("element", "by " + selector.describe());
            return selector;
        }
        String rendered = by.toString();
        int colon = rendered.indexOf(':');
        String kind = colon < 0 ? rendered : rendered.substring(0, colon).trim();
        String value = colon < 0 ? "" : rendered.substring(colon + 1).trim();
        String lower = kind.toLowerCase(Locale.ROOT);
        CompositeSelector selector;
        if (lower.contains("xpath")) {
            selector = new CompositeSelector(executableXPath(value), null, null, "web", null, rendered);
        } else if (lower.contains("css")) {
            selector = new CompositeSelector(null, value, null, "web", null, rendered);
        } else if (lower.contains("id")) {
            selector = new CompositeSelector(null, "#" + cssEscape(value), null, "web", null, rendered);
        } else if (lower.contains("classname")) {
            selector = new CompositeSelector(null, "." + cssEscape(value), null, "web", null, rendered);
        } else if (lower.contains("tag")) {
            selector = new CompositeSelector(null, value, null, "web", null, rendered);
        } else if (lower.contains("name")) {
            selector = new CompositeSelector(null, "[name=\"" + value.replace("\"", "\\\"") + "\"]", null, "web", null, rendered);
        } else if (lower.contains("partiallink")) {
            selector = new CompositeSelector(null, null, "role=link >> text~=" + quote(value), "web", null, rendered);
        } else if (lower.contains("link")) {
            selector = new CompositeSelector(null, null, "role=link >> text=" + quote(value), "web", null, rendered);
        } else {
            selector = new CompositeSelector(null, null, "desc=" + quote(rendered), "web", null, rendered);
        }
        CompositeLog.debug("element", "by " + selector.describe());
        return selector;
    }

    public static CompositeSelector phrase(ElementPhrase phrase) {
        if (phrase == null) {
            return new CompositeSelector(null, null, null, "web", null, "empty");
        }
        String look = lookStage(phrase.look());
        if (pureCss(phrase, look)) {
            CompositeSelector exact = new CompositeSelector(null, phrase.css(), null, source(phrase), ordinal(phrase), label(phrase));
            CompositeLog.debug("element", "css passthrough " + exact.describe());
            return exact;
        }
        if (pureXPath(phrase, look)) {
            XPathDefinitions.deprecatedOnce(label(phrase), phrase.xpath());
            CompositeSelector exact = new CompositeSelector(executableXPath(phrase.xpath()), null, null, source(phrase), ordinal(phrase), label(phrase));
            CompositeLog.debug("element", "xpath passthrough " + exact.describe());
            return exact;
        }
        List<String> stages = new ArrayList<>();
        if ("desktop".equals(source(phrase))) stages.add("source=desktop");
        if (phrase.template() != null && !phrase.template().isBlank()) {
            stages.add("image=template=" + phrase.template().trim());
        }
        if (phrase.ocr() != null && !phrase.ocr().isBlank()) {
            stages.add("image=ocr~=" + quote(phrase.ocr().trim()));
        }
        String word = phrase.contextWord() == null ? "" : phrase.contextWord().trim().toLowerCase(Locale.ROOT);
        String context = group(contextClauses(phrase));
        String target = group(targetClauses(phrase, look));
        switch (word) {
            case "before" -> {
                add(stages, target);
                stages.add("below=1");
                add(stages, context);
            }
            case "after" -> {
                add(stages, context);
                stages.add("below=1");
                add(stages, target);
            }
            case "in", "from", "for" -> {
                add(stages, context);
                add(stages, target);
            }
            default -> add(stages, target);
        }
        String pipeline = String.join(" >> ", stages.stream().filter(stage -> stage != null && !stage.isBlank()).toList());
        String xpath = phrase.xpath() != null && !phrase.xpath().isBlank() ? executableXPath(phrase.xpath()) : null;
        if (!pipeline.isBlank()) {
            CompositeLog.info("query", "combined " + pipeline + " fallback=" + (xpath != null));
        }
        CompositeSelector selector = new CompositeSelector(xpath, null, pipeline.isBlank() ? null : pipeline, source(phrase), ordinal(phrase), label(phrase));
        if (look != null) CompositeLog.info("query", "forward selector=" + pipeline);
        CompositeLog.debug("element", "phrase " + selector.describe());
        return selector;
    }

    private static boolean pureCss(ElementPhrase phrase, String look) {
        return phrase.css() != null && !phrase.css().isBlank() && look == null && !hasXPath(phrase)
                && !hasText(phrase) && !hasCategory(phrase) && state(phrase.state()) == null
                && !hasContext(phrase) && !hasImage(phrase);
    }

    private static boolean pureXPath(ElementPhrase phrase, String look) {
        return hasXPath(phrase) && look == null && (phrase.css() == null || phrase.css().isBlank())
                && !hasText(phrase) && !hasCategory(phrase) && state(phrase.state()) == null
                && !hasContext(phrase) && !hasImage(phrase);
    }

    private static boolean hasXPath(ElementPhrase phrase) {
        return phrase.xpath() != null && !phrase.xpath().isBlank();
    }

    private static boolean hasText(ElementPhrase phrase) {
        return phrase.text() != null && !phrase.text().isBlank();
    }

    private static boolean hasCategory(ElementPhrase phrase) {
        return phrase.category() != null && !phrase.category().isBlank();
    }

    private static boolean hasContext(ElementPhrase phrase) {
        return (phrase.contextWord() != null && !phrase.contextWord().isBlank())
                || (phrase.contextText() != null && !phrase.contextText().isBlank())
                || (phrase.contextCategory() != null && !phrase.contextCategory().isBlank());
    }

    private static boolean hasImage(ElementPhrase phrase) {
        return "desktop".equals(source(phrase))
                || (phrase.template() != null && !phrase.template().isBlank())
                || (phrase.ocr() != null && !phrase.ocr().isBlank());
    }

    private static void add(List<String> stages, String clause) {
        if (clause != null && !clause.isBlank()) stages.add(clause);
    }

    private static String group(List<String> clauses) {
        if (clauses.isEmpty()) return null;
        if (clauses.size() == 1) return clauses.get(0);
        return "(" + String.join(" AND ", clauses) + ")";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** English {@code with look color equaling red} becomes {@code look=color=red}. */
    static String lookStage(String clause) {
        if (clause == null || clause.isBlank()) return null;
        String trimmed = clause.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("look=")) return trimmed;
        Matcher matcher = LOOK_CLAUSE.matcher(trimmed);
        StringBuilder stage = new StringBuilder();
        int end = 0;
        boolean any = false;
        while (matcher.find()) {
            if (any) {
                String between = trimmed.substring(end, matcher.start()).toLowerCase(Locale.ROOT);
                stage.append(between.contains(" or ") ? "|" : ",");
            }
            any = true;
            end = matcher.end();
            String property = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = cleanLookValue(matcher.group(3));
            if (stage.length() == 0) stage.append("look=");
            stage.append(property).append(lookOperator(matcher.group(2))).append(value);
        }
        return any ? stage.toString() : null;
    }

    private static String lookOperator(String words) {
        String name = words.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return switch (name) {
            case "greater than" -> ">";
            case "less than" -> "<";
            case "at least" -> ">=";
            case "at most" -> "<=";
            case "starting with" -> "^=";
            case "ending with" -> "$=";
            case "containing" -> "~=";
            default -> "=";
        };
    }

    private static String cleanLookValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                value = value.substring(1, value.length() - 1);
            }
        }
        while (value.endsWith(".") || value.endsWith(",") || value.endsWith("!")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static List<String> contextClauses(ElementPhrase phrase) {
        List<String> clauses = new ArrayList<>();
        String role = role(phrase.contextCategory());
        if (role != null) clauses.add(role);
        else if (phrase.contextCategory() != null && !phrase.contextCategory().isBlank()) {
            clauses.add("desc=" + quote(phrase.contextCategory().trim()));
        }
        if (phrase.contextText() != null && !phrase.contextText().isBlank()) {
            clauses.add("text=" + quote(fold(phrase.contextText().trim(), phrase.quote())));
        }
        return clauses;
    }

    private static List<String> targetClauses(ElementPhrase phrase, String look) {
        List<String> clauses = new ArrayList<>();
        String role = role(phrase.category());
        if (role != null) clauses.add(role);
        else if (phrase.category() != null && !phrase.category().isBlank()) {
            clauses.add("desc=" + quote(phrase.category().trim()));
        }
        String state = state(phrase.state());
        if (state != null) clauses.add(state);
        if (phrase.text() != null && !phrase.text().isBlank()) clauses.add(textStage(phrase));
        if (look != null) clauses.add(look);
        if (phrase.css() != null && !phrase.css().isBlank()) clauses.add("css=" + phrase.css().trim());
        return clauses;
    }

    private static String textStage(ElementPhrase phrase) {
        String mode = phrase.match() == null ? "equals" : phrase.match().trim().toLowerCase(Locale.ROOT);
        String value = fold(phrase.text().trim(), phrase.quote());
        return switch (mode) {
            case "containing", "contains" -> "text~=" + quote(value);
            case "starting", "starts" -> "text^=" + quote(value);
            case "matching", "matches" -> "text=" + quote("^" + value + "$");
            default -> "text=" + quote(value);
        };
    }

    private static String fold(String text, String quote) {
        if ("single".equalsIgnoreCase(quote)) return text.toLowerCase(Locale.ROOT);
        return text;
    }

    private static String state(String word) {
        if (word == null || word.isBlank()) return null;
        return switch (word.trim().toLowerCase(Locale.ROOT)) {
            case "checked", "selected", "on" -> "a11y=checked";
            case "unchecked", "unselected", "off" -> "a11y=checked=false";
            case "disabled" -> "a11y=disabled";
            case "enabled" -> "a11y=disabled=false";
            case "expanded" -> "a11y=expanded";
            case "collapsed" -> "a11y=expanded=false";
            case "required" -> "a11y=required";
            default -> "a11y=" + word.trim().toLowerCase(Locale.ROOT);
        };
    }

    static Integer ordinal(ElementPhrase phrase) {
        String word = phrase.ordinal();
        if (word == null || word.isBlank()) return null;
        String value = word.trim().toLowerCase(Locale.ROOT);
        if (value.equals("first")) return 0;
        if (value.equals("last")) return -1;
        String digits = value.replaceAll("(st|nd|rd|th)$", "");
        try {
            int index = Integer.parseInt(digits);
            return Math.max(0, index - 1);
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    /** Composite rejects {@code not (} and the same space on other XPath functions. */
    static String executableXPath(String xpath) {
        if (xpath == null || xpath.isBlank()) return xpath;
        return FUNCTION_SPACE.matcher(xpath).replaceAll("$1(");
    }

    private static String role(String category) {
        if (category == null || category.isBlank()) return null;
        return ROLES.get(category.trim().toLowerCase(Locale.ROOT));
    }

    private static String source(ElementPhrase phrase) {
        return "desktop".equalsIgnoreCase(phrase.source()) ? "desktop" : "web";
    }

    private static String label(ElementPhrase phrase) {
        String category = phrase.category() == null ? "element" : phrase.category().trim();
        if (phrase.text() == null || phrase.text().isBlank()) return "the " + category;
        return "the \"" + phrase.text().trim() + "\" " + category;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String cssEscape(String value) {
        return value.replaceAll("([^a-zA-Z0-9_-])", "\\\\$1");
    }

    private static void role(String category, String aria) {
        ROLES.put(category, "role=" + aria);
    }
}
