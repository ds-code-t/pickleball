package tools.dscode.control.api;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.DefinitionContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only live element counts. Builds an XPath with the execution dictionary,
 * then calls {@code findElements} and {@code isDisplayed}. It does not click,
 * type, or add a wait.
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
        ExecutionDictionary dictionary = DefinitionContext.getExecutionDictionary();
        ValueWrapper value = text == null || text.isBlank() ? null : ValueWrapper.createValueWrapper(text);
        ExecutionDictionary.CategoryResolution resolution =
                dictionary.getFinalCategoryResolution(category.trim(), value, null);
        String xpath = resolution.xpath() == null || resolution.xpath().getXpath() == null
                ? ""
                : resolution.xpath().getXpath();
        int raw = 0;
        int visible = 0;
        if (!xpath.isBlank()) {
            for (WebElement element : findElements(driver, xpath)) {
                raw++;
                if (displayed(element)) visible++;
            }
        }
        return new Hit(phrase(category.trim(), text), xpath, raw, visible);
    }

    public static List<Hit> inspect(WebDriver driver) {
        if (driver == null) {
            throw new IllegalStateException("The active scenario has not created a browser.");
        }
        List<Hit> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (WebElement element : findElements(driver, INTERACTIVE_XPATH)) {
            String category = category(element);
            String text = text(element);
            if (category.isEmpty() || text.isEmpty()) continue;
            if (!seen.add(category + "\0" + text)) continue;
            hits.add(find(driver, category, text));
        }
        return List.copyOf(hits);
    }

    private static List<WebElement> findElements(WebDriver driver, String xpath) {
        Duration previous = null;
        try {
            previous = driver.manage().timeouts().getImplicitWaitTimeout();
            driver.manage().timeouts().implicitlyWait(Duration.ZERO);
        } catch (RuntimeException ignored) {
            previous = null;
        }
        try {
            List<WebElement> found = driver.findElements(By.xpath(xpath));
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

    private static boolean displayed(WebElement element) {
        try {
            return element.isDisplayed();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String text(WebElement element) {
        String value = safe(element::getText);
        if (!value.isBlank()) return value.trim();
        for (String name : List.of("aria-label", "placeholder", "name", "id", "value")) {
            value = attribute(element, name);
            if (!value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String attribute(WebElement element, String name) {
        try {
            String value = element.getDomAttribute(name);
            if (value == null || value.isBlank()) value = element.getAttribute(name);
            return value == null ? "" : value;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String safe(java.util.function.Supplier<String> action) {
        try {
            String value = action.get();
            return value == null ? "" : value;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String category(WebElement element) {
        String tag = safe(element::getTagName).toLowerCase(Locale.ROOT);
        String role = attribute(element, "role").toLowerCase(Locale.ROOT);
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
