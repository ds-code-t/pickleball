package tools.dscode.control.api;

import org.openqa.selenium.By;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.common.composite.ElementOperations;
import tools.dscode.common.composite.ElementView;
import tools.dscode.common.composite.EngineQuery;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.coredefinitions.BrowserSteps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;
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
        return inspect(category, text, operation, maxElements, null);
    }

    public static ControlCallResult<ElementInspection> inspect(
            String category,
            String text,
            String operation,
            Integer maxElements,
            EngineQuery query
    ) {
        if (query != null && !query.text().isBlank()) {
            return inspectEngine(query, maxElements);
        }
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
            CompositeLog.info("query", "category " + category.trim());
            List<ElementView> views = ElementOperations.describe(driver, By.xpath(xpath));
            int limit = boundedMax(maxElements);
            List<ElementEvidence> evidence = new ArrayList<>();
            for (int index = 0; index < Math.min(limit, views.size()); index++) {
                evidence.add(evidence(views.get(index), index + 1));
            }
            return ControlCallResult.success(new ElementInspection(
                    category.trim(),
                    text == null ? "" : text,
                    op == null ? "DEFAULT" : op.name(),
                    clip(xpath, MAX_TEXT_CHARS),
                    views.size(),
                    views.size() > evidence.size(),
                    evidence
            ));
        } catch (Throwable failure) {
            return ControlCallResult.failed(failure);
        }
    }

    public static ElementEvidence evidence(ElementView view, int index) {
        String outerHtml = view.outerHtml() == null ? "" : view.outerHtml();
        boolean outerHtmlTruncated = outerHtml.length() > MAX_OUTER_HTML_CHARS;
        if (outerHtmlTruncated) {
            outerHtml = outerHtml.substring(0, MAX_OUTER_HTML_CHARS);
        }
        String value = view.value();
        if (value.isBlank()) value = view.attribute("value");
        return new ElementEvidence(
                index,
                view.tag(),
                clip(view.text(), MAX_TEXT_CHARS),
                clip(value, MAX_TEXT_CHARS),
                view.displayed(),
                view.enabled(),
                view.selected(),
                view.x(),
                view.y(),
                view.width(),
                view.height(),
                attributes(view),
                outerHtml,
                outerHtmlTruncated,
                view.handle(),
                view.rank(),
                view.score(),
                view.humanCodes(),
                view.humanSentence(),
                view.visualOnly()
        );
    }

    private static ControlCallResult<ElementInspection> inspectEngine(EngineQuery query, Integer maxElements) {
        RemoteWebDriver driver = BrowserSteps.getCurrentDriverIfPresent();
        if (driver == null) {
            return ControlCallResult.unavailable("The active scenario has not created a browser.");
        }
        try {
            int limit = boundedMax(maxElements);
            EngineQuery used = query.limit() == null ? query.limit(limit) : query;
            List<ElementView> views = ElementOperations.describe(driver, used);
            List<ElementEvidence> evidence = new ArrayList<>();
            for (int index = 0; index < Math.min(limit, views.size()); index++) {
                evidence.add(evidence(views.get(index), index + 1));
            }
            return ControlCallResult.success(new ElementInspection(
                    "",
                    "",
                    "ENGINE",
                    used.text(),
                    views.size(),
                    views.size() > evidence.size(),
                    evidence,
                    used.text()
            ));
        } catch (Throwable failure) {
            return ControlCallResult.failed(failure);
        }
    }

    private static Map<String, String> attributes(ElementView view) {
        TreeMap<String, String> sorted = new TreeMap<>();
        view.attributes().forEach((key, value) -> {
            if (key != null && sorted.size() < MAX_ATTRIBUTES) {
                sorted.put(key, clip(value == null ? "" : value, MAX_ATTRIBUTE_VALUE_CHARS));
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

    private static String clip(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "\n...[truncated]";
    }
}
