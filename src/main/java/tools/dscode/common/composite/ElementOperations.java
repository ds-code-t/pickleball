package tools.dscode.common.composite;

import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import tools.dscode.common.seleniumextensions.ElementWrapper;

import java.time.Duration;
import java.util.List;

/**
 * Single entry for element lookup and actions. Composite when the binary is
 * up. The Selenium calls in this class are the legacy path for a run that
 * does not have the binary.
 */
public final class ElementOperations {
    private ElementOperations() {
    }

    public static boolean primary() {
        return CompositeRuntime.primary();
    }

    public static List<WebElement> findElements(SearchContext context, By by) {
        if (by == null) return List.of();
        if (primary()) {
            CompositeLog.debug("element", "find " + by);
            if (context instanceof CompositeWebElement composite) return composite.findElements(by);
            if (context == null || context instanceof WebDriver) return DomCompositeAdapter.get().find(by);
            return context.findElements(by);
        }
        if (context == null) return List.of();
        return context.findElements(by);
    }

    public static WebElement findElement(SearchContext context, By by) {
        if (!primary() || (context != null && !(context instanceof CompositeWebElement) && !(context instanceof WebDriver))) {
            return context.findElement(by);
        }
        List<WebElement> found = findElements(context, by);
        if (found.isEmpty()) throw new NoSuchElementException("No composite match for " + by);
        return found.get(0);
    }

    public static void enterFrame(WebDriver driver, WebElement frame) {
        WebElement raw = unwrap(frame);
        if (raw instanceof CompositeWebElement composite) {
            composite.enterFrame();
            return;
        }
        driver.switchTo().frame(raw);
    }

    public static SearchContext shadowRoot(WebElement host) {
        return unwrap(host).getShadowRoot();
    }

    public static void click(WebDriver driver, WebElement element) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) {
            composite.click();
            return;
        }
        if (primary()) {
            CompositeLog.errorFix("element", "click had no composite element", "Look the element up through ElementOperations so the hit stays on the adapter.");
        }
        legacyClick(driver, raw);
    }

    public static void sendKeys(WebDriver driver, WebElement element, CharSequence... keys) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) {
            composite.sendKeys(keys);
            return;
        }
        if (primary() && (element == null)) {
            DomCompositeAdapter.get().keys(text(keys));
            return;
        }
        if (raw == null) {
            new Actions(driver).sendKeys(keys).perform();
            return;
        }
        raw.sendKeys(keys);
    }

    public static void hover(WebDriver driver, WebElement element) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) {
            DomCompositeAdapter.get().hover(composite);
            return;
        }
        new Actions(driver).moveToElement(raw).perform();
    }

    public static void drag(WebDriver driver, WebElement source, WebElement target, int xOffset, int yOffset) {
        WebElement from = unwrap(source);
        WebElement to = target == null ? null : unwrap(target);
        if (from instanceof CompositeWebElement composite) {
            DomCompositeAdapter.get().drag(composite, to instanceof CompositeWebElement targetElement ? targetElement : null, xOffset, yOffset);
            return;
        }
        Actions actions = new Actions(driver).clickAndHold(from).moveByOffset(xOffset, yOffset);
        if (to != null) actions.moveToElement(to);
        actions.release().perform();
    }

    public static void scroll(WebDriver driver, WebElement element, int deltaY) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) {
            DomCompositeAdapter.get().scroll(composite, 0, deltaY);
            return;
        }
        new Actions(driver).scrollByAmount(0, deltaY).perform();
    }

    public static void keys(WebDriver driver, CharSequence... keys) {
        if (primary()) {
            DomCompositeAdapter.get().keys(text(keys));
            return;
        }
        new Actions(driver).sendKeys(keys).perform();
    }

    public static WebElement waitFor(SearchContext context, By by, Duration timeout) {
        long deadline = System.nanoTime() + (timeout == null ? Duration.ofSeconds(5) : timeout).toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                List<WebElement> found = findElements(context, by);
                if (!found.isEmpty()) return found.get(0);
            } catch (RuntimeException failure) {
                last = failure;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (last != null) throw last;
        throw new NoSuchElementException("No match for " + by + " before " + timeout);
    }

    public static JsonDesktop desktop(String verb, ElementPhrase phrase) {
        return new JsonDesktop(DomCompositeAdapter.get().desktop(verb, phrase));
    }

    private static void legacyClick(WebDriver driver, WebElement element) {
        new Actions(driver).moveToElement(element).click(element).perform();
    }

    private static WebElement unwrap(WebElement element) {
        if (element instanceof ElementWrapper wrapper) return wrapper.getWrappedElement();
        return element;
    }

    private static String text(CharSequence... keys) {
        StringBuilder text = new StringBuilder();
        if (keys != null) {
            for (CharSequence key : keys) {
                if (key != null) text.append(key);
            }
        }
        return text.toString();
    }

    /** Thin holder so callers do not need the Jackson type. */
    public record JsonDesktop(com.fasterxml.jackson.databind.JsonNode node) {
        public int size() {
            com.fasterxml.jackson.databind.JsonNode list = node == null ? null : node.get("composite");
            return list != null && list.isArray() ? list.size() : 0;
        }
    }
}
