package tools.dscode.control.api;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.coredefinitions.BrowserSteps;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;
import static tools.dscode.common.domoperations.SeleniumUtils.safeDomAttributeOrProperty;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;

/** Read-only tooling access to the same execution dictionary used by normal Pickleball element syntax. */
public final class ElementControl {
    private static final int DEFAULT_MAX_ELEMENTS = 20;
    private static final int MAX_ELEMENTS = 100;
    private static final int MAX_TEXT_CHARS = 8 * 1024;
    private static final int MAX_OUTER_HTML_CHARS = 16 * 1024;
    private static final int MAX_ATTRIBUTES = 32;
    private static final int MAX_ATTRIBUTE_VALUE_CHARS = 2 * 1024;

    private ElementControl() {
    }

    public static ControlCallResult<ElementInspection> inspect(
            String category,
            String text,
            String operation,
            Integer maxElements
    ) {
        if (category == null || category.isBlank()) {
            return ControlCallResult.unavailable("Element category must not be blank.");
        }
        RemoteWebDriver driver = BrowserSteps.getCurrentDriverIfPresent();
        if (driver == null) {
            return ControlCallResult.unavailable("The active scenario has not created a browser.");
        }

        try {
            ExecutionDictionary dictionary = getExecutionDictionary();
            ExecutionDictionary.Op op = resolveOperation(operation);
            ValueWrapper value = text == null || text.isBlank()
                    ? null
                    : createValueWrapper(text);
            ExecutionDictionary.CategoryResolution resolution =
                    dictionary.getFinalCategoryResolution(category.trim(), value, op);
            if (resolution.xpath() == null || resolution.xpath().getXpath() == null) {
                return ControlCallResult.unavailable(
                        "Pickleball could not resolve element category " + category.trim() + "."
                );
            }

            String xpath = resolution.xpath().getXpath();
            List<WebElement> matches = driver.findElements(By.xpath(xpath));
            int limit = boundedMax(maxElements);
            List<ElementEvidence> evidence = java.util.stream.IntStream.range(0, Math.min(limit, matches.size()))
                    .mapToObj(index -> evidence(driver, matches.get(index), index + 1))
                    .toList();
            return ControlCallResult.success(new ElementInspection(
                    category.trim(),
                    text == null ? "" : text,
                    op == null ? "DEFAULT" : op.name(),
                    clip(xpath, MAX_TEXT_CHARS),
                    matches.size(),
                    matches.size() > evidence.size(),
                    evidence
            ));
        } catch (Throwable failure) {
            return ControlCallResult.failed(failure);
        }
    }

    private static ElementEvidence evidence(
            RemoteWebDriver driver,
            WebElement element,
            int index
    ) {
        Rectangle rect = element.getRect();
        String outerHtml = Objects.toString(
                safeDomAttributeOrProperty(element, "outerHTML"),
                ""
        );
        boolean outerHtmlTruncated = outerHtml.length() > MAX_OUTER_HTML_CHARS;
        if (outerHtmlTruncated) {
            outerHtml = outerHtml.substring(0, MAX_OUTER_HTML_CHARS);
        }

        return new ElementEvidence(
                index,
                safe(() -> element.getTagName()),
                clip(safe(element::getText), MAX_TEXT_CHARS),
                clip(Objects.toString(safeDomAttributeOrProperty(element, "value"), ""), MAX_TEXT_CHARS),
                safeBoolean(element::isDisplayed),
                safeBoolean(element::isEnabled),
                safeBoolean(element::isSelected),
                rect.getX(),
                rect.getY(),
                rect.getWidth(),
                rect.getHeight(),
                attributes(driver, element),
                outerHtml,
                outerHtmlTruncated
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> attributes(RemoteWebDriver driver, WebElement element) {
        JavascriptExecutor javascript = driver;
        Object raw = javascript.executeScript(
                "const out={}; for (const a of arguments[0].attributes) out[a.name]=a.value; return out;",
                element
        );
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }

        TreeMap<String, String> sorted = new TreeMap<>();
        map.forEach((key, value) -> {
            if (key != null && sorted.size() < MAX_ATTRIBUTES) {
                sorted.put(
                        String.valueOf(key),
                        clip(Objects.toString(value, ""), MAX_ATTRIBUTE_VALUE_CHARS)
                );
            }
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static ExecutionDictionary.Op resolveOperation(String operation) {
        if (operation == null || operation.isBlank() || "DEFAULT".equalsIgnoreCase(operation.trim())) {
            return null;
        }
        String normalized = operation.trim();
        ExecutionDictionary.Op parsed = ExecutionDictionary.Op.getOpFromString(normalized);
        if (parsed != null) {
            return parsed;
        }
        try {
            return ExecutionDictionary.Op.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unknown Pickleball element operation: " + operation, failure);
        }
    }

    private static int boundedMax(Integer requested) {
        int value = requested == null ? DEFAULT_MAX_ELEMENTS : requested;
        if (value < 1 || value > MAX_ELEMENTS) {
            throw new IllegalArgumentException("maxElements must be between 1 and " + MAX_ELEMENTS + ".");
        }
        return value;
    }

    private static boolean safeBoolean(BooleanSupplier action) {
        try {
            return action.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String safe(java.util.function.Supplier<String> action) {
        try {
            return Objects.toString(action.get(), "");
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String clip(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "\n...[truncated]";
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
