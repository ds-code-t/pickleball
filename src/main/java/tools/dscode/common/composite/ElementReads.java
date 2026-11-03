package tools.dscode.common.composite;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The only batched DOM read. Callers use {@link ElementOperations#describe}.
 * Composite hits are copied off the query payload (no second round trip).
 * A Selenium driver runs one script. A driver that cannot run the script
 * falls back to property reads in this class, which is the
 * {@code pkb_composite=off} path.
 */
final class ElementReads {
    static final AtomicInteger BATCHES = new AtomicInteger();

    private static final String QUERY_JS = """
            var xpath = arguments[0];
            var css = arguments[1];
            var nodes = [];
            if (xpath) {
              var snap = document.evaluate(xpath, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
              for (var i = 0; i < snap.snapshotLength; i++) nodes.push(snap.snapshotItem(i));
            } else if (css) {
              nodes = Array.prototype.slice.call(document.querySelectorAll(css));
            }
            return nodes.map(pickleballDescribe);
            """;

    private static final String ELEMENTS_JS = """
            var nodes = arguments[0] || [];
            return Array.prototype.map.call(nodes, pickleballDescribe);
            """;

    private static final String DESCRIBE_FN = """
            function pickleballDescribe(el) {
              if (!el || el.nodeType !== 1) {
                return {tag:'', text:'', displayed:false, enabled:false, selected:false, attributes:{}, value:'', outerHtml:'', x:0, y:0, width:0, height:0};
              }
              var attrs = [];
              if (el.attributes) {
                for (var i = 0; i < el.attributes.length; i++) {
                  var a = el.attributes[i];
                  attrs.push([String(a.name), a.value == null ? '' : String(a.value)]);
                }
              }
              var rect = el.getBoundingClientRect();
              var tag = String(el.tagName || '').toLowerCase();
              var text = el.textContent == null ? '' : String(el.textContent);
              if (el.innerText != null && String(el.innerText).length > 0) text = String(el.innerText);
              var value = el.value == null ? '' : String(el.value);
              return {
                tag: tag,
                text: text,
                displayed: pickleballDisplayed(el),
                enabled: !el.disabled,
                selected: !!(el.selected || el.checked),
                attributes: attrs,
                value: value,
                outerHtml: el.outerHTML || '',
                x: Math.round(rect.x || 0),
                y: Math.round(rect.y || 0),
                width: Math.round(rect.width || 0),
                height: Math.round(rect.height || 0)
              };
            }
            function pickleballDisplayed(el) {
              if (!el) return false;
              if (el.isConnected === false) return false;
              for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
                var style = null;
                try { style = window.getComputedStyle(n); } catch (ignore) { style = null; }
                if (!style) break;
                var display = style.display;
                var visibility = style.visibility;
                if (display === 'none' || visibility === 'hidden') return false;
              }
              var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
              if (rect && ((rect.width || 0) > 0 || (rect.height || 0) > 0)) return true;
              if (el.offsetWidth || el.offsetHeight) return true;
              return !!(el.getClientRects && el.getClientRects().length);
            }
            """;

    private ElementReads() {
    }

    static List<ElementView> describe(WebDriver driver, By by) {
        long start = System.nanoTime();
        BATCHES.incrementAndGet();
        String selector = by == null ? "" : by.toString();
        String mode = "selenium-legacy";
        List<ElementView> views;
        if (ElementOperations.primary()) {
            mode = "composite";
            views = fromElements(ElementOperations.findElements(driver, by));
        } else if (driver instanceof JavascriptExecutor && locatable(by)) {
            try {
                mode = "selenium-batch";
                views = scriptQuery((JavascriptExecutor) driver, by);
            } catch (RuntimeException failure) {
                mode = "selenium-legacy";
                String detail = failure.getClass().getSimpleName() + ":" + failure.getMessage();
                if (detail.length() > 160) detail = detail.substring(0, 160);
                selector = selector + " scriptError=" + detail.replace('\n', ' ');
                views = fromElements(ElementOperations.findElements(driver, by));
            }
        } else {
            views = fromElements(ElementOperations.findElements(driver, by));
        }
        log(mode, selector, views.size(), start);
        return views;
    }

    static List<ElementView> describe(WebDriver driver, List<WebElement> elements) {
        long start = System.nanoTime();
        BATCHES.incrementAndGet();
        List<WebElement> found = elements == null ? List.of() : elements;
        boolean composite = !found.isEmpty() && found.stream().allMatch(element -> unwrap(element) instanceof CompositeWebElement);
        String mode;
        List<ElementView> views;
        if (composite || found.isEmpty()) {
            mode = composite ? "composite" : "selenium-legacy";
            views = fromElements(found);
        } else if (driver instanceof JavascriptExecutor js) {
            try {
                mode = "selenium-batch";
                views = parse(js.executeScript(DESCRIBE_FN + ELEMENTS_JS, found));
            } catch (RuntimeException failure) {
                mode = "selenium-legacy";
                views = fromElements(found);
            }
        } else {
            mode = "selenium-legacy";
            views = fromElements(found);
        }
        log(mode, "elements", views.size(), start);
        return views;
    }

    static ElementView fromComposite(CompositeWebElement element) {
        String value = element.getAttribute("value");
        String outer = element.getAttribute("outerHTML");
        return new ElementView(
                element.getTagName(),
                element.getText(),
                element.isDisplayed(),
                element.isEnabled(),
                element.isSelected(),
                element.attributes(),
                element.getLocation().getX(),
                element.getLocation().getY(),
                element.getSize().getWidth(),
                element.getSize().getHeight(),
                value == null ? "" : value,
                outer == null ? "" : outer
        );
    }

    static String text(WebElement element) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) {
            String value = composite.getText();
            return value == null ? "" : value;
        }
        String value = raw.getText();
        return value == null ? "" : value;
    }

    static String textContent(WebDriver driver, WebElement element) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement) return text(raw);
        if (driver instanceof JavascriptExecutor) {
            try {
                Object content = ((JavascriptExecutor) driver).executeScript(
                        "var el = arguments[0]; return el && el.textContent == null ? '' : (el ? el.textContent : '');",
                        raw
                );
                if (content != null && !content.toString().isBlank()) return content.toString();
            } catch (RuntimeException ignored) {
                // Fall through to the visible text.
            }
        }
        return text(raw);
    }

    static String tag(WebElement element) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) return composite.getTagName();
        return raw.getTagName();
    }

    static boolean displayed(WebElement element) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) return composite.isDisplayed();
        return raw.isDisplayed();
    }

    static boolean enabled(WebElement element) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) return composite.isEnabled();
        return raw.isEnabled();
    }

    static boolean selected(WebElement element) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) return composite.isSelected();
        return raw.isSelected();
    }

    static String attribute(WebElement element, String name) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) return composite.getAttribute(name);
        return raw.getAttribute(name);
    }

    static String domAttribute(WebElement element, String name) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) return composite.getDomAttribute(name);
        return raw.getDomAttribute(name);
    }

    static String domProperty(WebElement element, String name) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) return composite.getDomProperty(name);
        return raw.getDomProperty(name);
    }

    static String css(WebElement element, String property) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) return composite.getCssValue(property);
        return raw.getCssValue(property);
    }

    static Rectangle rect(WebElement element) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) return composite.getRect();
        return raw.getRect();
    }

    private static List<ElementView> scriptQuery(JavascriptExecutor javascript, By by) {
        String[] locator = split(by);
        Object raw = javascript.executeScript(
                DESCRIBE_FN + QUERY_JS,
                locator[0] == null ? "" : locator[0],
                locator[1] == null ? "" : locator[1]);
        return parse(raw);
    }

    private static boolean locatable(By by) {
        if (by == null) return false;
        String raw = by.toString();
        return raw.startsWith("By.xpath: ") || raw.startsWith("By.cssSelector: ");
    }

    private static String[] split(By by) {
        String raw = by.toString();
        if (raw.startsWith("By.xpath: ")) return new String[]{raw.substring("By.xpath: ".length()), null};
        if (raw.startsWith("By.cssSelector: ")) return new String[]{null, raw.substring("By.cssSelector: ".length())};
        return new String[]{null, null};
    }

    private static List<ElementView> fromElements(List<WebElement> elements) {
        if (elements == null || elements.isEmpty()) return List.of();
        List<ElementView> views = new ArrayList<>(elements.size());
        for (WebElement element : elements) {
            WebElement raw = unwrap(element);
            if (raw instanceof CompositeWebElement composite) views.add(fromComposite(composite));
            else views.add(fromSelenium(raw));
        }
        return views;
    }

    private static ElementView fromSelenium(WebElement element) {
        Rectangle rect = call(() -> element.getRect(), null);
        String value = call(() -> element.getDomAttribute("value"), null);
        if (value == null || value.isBlank()) value = call(() -> element.getAttribute("value"), "");
        String outer = call(() -> element.getAttribute("outerHTML"), "");
        return new ElementView(
                call(() -> element.getTagName(), ""),
                call(() -> element.getText(), ""),
                callBool(() -> element.isDisplayed()),
                callBool(() -> element.isEnabled()),
                callBool(() -> element.isSelected()),
                Map.of(),
                rect == null ? 0 : rect.getX(),
                rect == null ? 0 : rect.getY(),
                rect == null ? 0 : rect.getWidth(),
                rect == null ? 0 : rect.getHeight(),
                value == null ? "" : value,
                outer == null ? "" : outer
        );
    }

    @SuppressWarnings("unchecked")
    private static List<ElementView> parse(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("error: batch describe returned "
                    + (raw == null ? "null" : raw.getClass().getName())
                    + ". fix: the describe script must return a list.");
        }
        List<ElementView> views = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalStateException("error: batch describe row was not an object. fix: return one object per node.");
            }
            views.add(new ElementView(
                    text(map.get("tag")),
                    text(map.get("text")),
                    bool(map.get("displayed")),
                    bool(map.get("enabled")),
                    bool(map.get("selected")),
                    attributes(map.get("attributes")),
                    number(map.get("x")),
                    number(map.get("y")),
                    number(map.get("width")),
                    number(map.get("height")),
                    text(map.get("value")),
                    text(map.get("outerHtml"))
            ));
        }
        return views;
    }

    private static Map<String, String> attributes(Object raw) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null && value != null) copy.put(String.valueOf(key), String.valueOf(value));
            });
            return copy.isEmpty() ? Map.of() : copy;
        }
        if (raw instanceof List<?> pairs) {
            for (Object item : pairs) {
                if (!(item instanceof List<?> pair) || pair.size() < 2) continue;
                if (pair.get(0) == null || pair.get(1) == null) continue;
                copy.put(String.valueOf(pair.get(0)), String.valueOf(pair.get(1)));
            }
            return copy.isEmpty() ? Map.of() : copy;
        }
        return Map.of();
    }

    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static boolean bool(Object raw) {
        return raw instanceof Boolean value ? value : "true".equalsIgnoreCase(String.valueOf(raw));
    }

    private static int number(Object raw) {
        if (raw instanceof Number number) return (int) Math.round(number.doubleValue());
        return 0;
    }

    private static WebElement unwrap(WebElement element) {
        if (element instanceof tools.dscode.common.seleniumextensions.ElementWrapper wrapper) {
            return wrapper.getWrappedElement();
        }
        return element;
    }

    private static void log(String mode, String selector, int nodes, long start) {
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        String shown = selector == null ? "" : selector;
        if (shown.length() > 180) shown = shown.substring(0, 180);
        CompositeLog.trace("batch", "mode=" + mode
                + " selector=" + shown
                + " nodes=" + nodes
                + " durationMs=" + durationMs);
    }

    private static <T> T call(java.util.function.Supplier<T> action, T fallback) {
        try {
            T value = action.get();
            return value == null ? fallback : value;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean callBool(java.util.function.BooleanSupplier action) {
        try {
            return action.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
