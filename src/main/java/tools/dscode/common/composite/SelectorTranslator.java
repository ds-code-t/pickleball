package tools.dscode.common.composite;

import org.openqa.selenium.By;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a Pickleball element phrase, or a Selenium {@link By} the execution
 * dictionary already built, onto one composite selector. XPath and CSS produced
 * by the dictionary pass through unchanged. Phrase text, quote type, context
 * words, and categories are translated here and nowhere else.
 */
public final class SelectorTranslator {
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

    private SelectorTranslator() {
    }

    public static CompositeSelector fromBy(By by) {
        if (by == null) {
            return new CompositeSelector(null, null, null, "web", null, "empty");
        }
        String rendered = by.toString();
        int colon = rendered.indexOf(':');
        String kind = colon < 0 ? rendered : rendered.substring(0, colon).trim();
        String value = colon < 0 ? "" : rendered.substring(colon + 1).trim();
        String lower = kind.toLowerCase(Locale.ROOT);
        CompositeSelector selector;
        if (lower.contains("xpath")) {
            selector = new CompositeSelector(value, null, null, "web", null, rendered);
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
        if (phrase.xpath() != null && !phrase.xpath().isBlank()) {
            CompositeSelector exact = new CompositeSelector(phrase.xpath(), null, null, source(phrase), ordinal(phrase), label(phrase));
            CompositeLog.debug("element", "xpath passthrough " + exact.describe());
            return exact;
        }
        if (phrase.css() != null && !phrase.css().isBlank()) {
            CompositeSelector exact = new CompositeSelector(null, phrase.css(), null, source(phrase), ordinal(phrase), label(phrase));
            CompositeLog.debug("element", "css passthrough " + exact.describe());
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
        List<String> context = contextStages(phrase);
        List<String> target = targetStages(phrase);
        switch (word) {
            case "before" -> {
                stages.addAll(target);
                stages.add("below=1");
                stages.addAll(context);
            }
            case "after" -> {
                stages.addAll(context);
                stages.add("below=1");
                stages.addAll(target);
            }
            case "in", "from", "for" -> {
                stages.addAll(context);
                stages.addAll(target);
            }
            default -> stages.addAll(target);
        }
        String pipeline = String.join(" >> ", stages.stream().filter(stage -> stage != null && !stage.isBlank()).toList());
        CompositeSelector selector = new CompositeSelector(null, null, pipeline, source(phrase), ordinal(phrase), label(phrase));
        CompositeLog.debug("element", "phrase " + selector.describe());
        return selector;
    }

    private static List<String> contextStages(ElementPhrase phrase) {
        List<String> stages = new ArrayList<>();
        String role = role(phrase.contextCategory());
        if (role != null) stages.add(role);
        else if (phrase.contextCategory() != null && !phrase.contextCategory().isBlank()) {
            stages.add("desc=" + quote(phrase.contextCategory().trim()));
        }
        if (phrase.contextText() != null && !phrase.contextText().isBlank()) {
            stages.add("text=" + quote(fold(phrase.contextText().trim(), phrase.quote())));
        }
        return stages;
    }

    private static List<String> targetStages(ElementPhrase phrase) {
        List<String> stages = new ArrayList<>();
        String role = role(phrase.category());
        if (role != null) stages.add(role);
        else if (phrase.category() != null && !phrase.category().isBlank()) {
            stages.add("desc=" + quote(phrase.category().trim()));
        }
        String state = state(phrase.state());
        if (state != null) stages.add(state);
        if (phrase.text() != null && !phrase.text().isBlank()) stages.add(textStage(phrase));
        return stages;
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
