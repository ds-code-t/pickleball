package tools.dscode.control.api;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.composite.ElementOperations;
import tools.dscode.common.composite.ElementView;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.DefinitionContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only live element counts. One {@link ElementOperations#describe} call
 * returns tag, text, displayed, and attributes. Nothing is clicked or typed.
 * Composite is used when that runtime is primary. The implicit wait is zeroed
 * around the legacy driver call.
 */
public final class PageElementQuery {
    private static final String INTERACTIVE_XPATH =
            "//a | //button | //input | //select | //textarea"
                    + " | //*[@role='button' or @role='link' or @role='textbox']";

    private PageElementQuery() {
    }

    public record Hit(String phrase, String xpath, int rawMatchCount, int visibleCount) {
    }

    public static Hit find(WebDriver driver, String category, String text) {
        if (driver == null) {
            throw new IllegalStateException("The active scenario has not created a browser.");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Element category must not be blank.");
        }
        String xpath = xpath(category.trim(), text);
        int raw = 0;
        int visible = 0;
        if (!xpath.isBlank()) {
            for (ElementView view : describe(driver, xpath)) {
                raw++;
                if (view.displayed()) visible++;
            }
        }
        return new Hit(phrase(category.trim(), text), xpath, raw, visible);
    }

    public static List<Hit> inspect(WebDriver driver) {
        if (driver == null) {
            throw new IllegalStateException("The active scenario has not created a browser.");
        }
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> texts = new LinkedHashMap<>();
        for (ElementView view : describe(driver, INTERACTIVE_XPATH)) {
            String category = category(view);
            String text = text(view);
            if (category.isEmpty() || text.isEmpty()) continue;
            String key = category + "\0" + text;
            int[] count = counts.computeIfAbsent(key, ignored -> new int[2]);
            count[0]++;
            if (view.displayed()) count[1]++;
            texts.putIfAbsent(key, text);
        }
        List<Hit> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            if (!seen.add(entry.getKey())) continue;
            String category = entry.getKey().substring(0, entry.getKey().indexOf('\0'));
            String text = texts.get(entry.getKey());
            int[] count = entry.getValue();
            hits.add(new Hit(phrase(category, text), xpath(category, text), count[0], count[1]));
        }
        return List.copyOf(hits);
    }

    private static List<ElementView> describe(WebDriver driver, String xpath) {
        Duration previous = null;
        try {
            previous = driver.manage().timeouts().getImplicitWaitTimeout();
            driver.manage().timeouts().implicitlyWait(Duration.ZERO);
        } catch (RuntimeException ignored) {
            previous = null;
        }
        try {
            List<ElementView> found = ElementOperations.describe(driver, By.xpath(xpath));
            return found == null ? List.of() : found;
        } finally {
            if (previous != null) {
                try {
                    driver.manage().timeouts().implicitlyWait(previous);
                } catch (RuntimeException ignored) {
                    // Leave the driver as the caller found it when timeouts cannot be restored.
                }
            }
        }
    }

    private static String xpath(String category, String text) {
        ExecutionDictionary dictionary = DefinitionContext.getExecutionDictionary();
        ValueWrapper value = text == null || text.isBlank() ? null : ValueWrapper.createValueWrapper(text);
        ExecutionDictionary.CategoryResolution resolution =
                dictionary.getFinalCategoryResolution(category, value, null);
        if (resolution.xpath() == null || resolution.xpath().getXpath() == null) return "";
        return resolution.xpath().getXpath();
    }

    private static String text(ElementView view) {
        if (!view.text().isBlank()) return view.text().trim();
        for (String name : List.of("aria-label", "placeholder", "name", "id", "value")) {
            String value = view.attribute(name);
            if (!value.isBlank()) return value.trim();
        }
        if (!view.value().isBlank()) return view.value().trim();
        return "";
    }

    private static String category(ElementView view) {
        String tag = view.tag().toLowerCase(Locale.ROOT);
        String role = view.attribute("role").toLowerCase(Locale.ROOT);
        if ("a".equals(tag) || "link".equals(role)) return "Link";
        if ("textarea".equals(tag) || "input".equals(tag) || "textbox".equals(role)) return "Textbox";
        if ("select".equals(tag)) return "Dropdown";
        if ("button".equals(tag) || "button".equals(role)) return "Button";
        return "";
    }

    static String phrase(String category, String text) {
        if (text == null || text.isBlank()) return "the " + category;
        return "the \"" + text.trim() + "\" " + category;
    }
}
