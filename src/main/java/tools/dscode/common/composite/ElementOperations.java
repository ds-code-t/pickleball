package tools.dscode.common.composite;

import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.Select;
import tools.dscode.common.domoperations.KeyParser;
import tools.dscode.common.seleniumextensions.ElementWrapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single entry for element lookup and actions. Composite when the binary is
 * up and a browser session is joined. A primary binary that has not joined a
 * browser uses the WebDriver's Selenium find. The other Selenium calls in
 * this class are the legacy path for a run that does not have the binary.
 */
public final class ElementOperations {
    private static final AtomicInteger FINDS = new AtomicInteger();
    private static final AtomicInteger STATE_FALLBACKS = new AtomicInteger();

    private ElementOperations() {
    }

    public static boolean primary() {
        return CompositeRuntime.primary();
    }

    public static List<WebElement> findElements(SearchContext context, By by) {
        FINDS.incrementAndGet();
        if (by == null) return List.of();
        if (unattachedDriver(context)) {
            CompositeLog.info("element", "find selenium unattached");
            return context.findElements(by);
        }
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
        if (unattachedDriver(context) || !primary() || (context != null && !(context instanceof CompositeWebElement) && !(context instanceof WebDriver))) {
            return context.findElement(by);
        }
        List<WebElement> found = findElements(context, by);
        if (found.isEmpty()) throw new NoSuchElementException("No composite match for " + by);
        return found.get(0);
    }

    /** Primary, but this driver has no joined CDP session. CompositeWebElement stays on the adapter. */
    private static boolean unattachedDriver(SearchContext context) {
        return context instanceof WebDriver && primary() && !CompositeRuntime.browserJoined();
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
        raw.click();
    }

    /**
     * Form submit. A composite element uses {@code submit} when {@code capabilities.actions}
     * lists it, and click otherwise. A Selenium element calls {@link WebElement#submit()}.
     */
    public static void submit(WebDriver driver, WebElement element) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) {
            composite.submit();
            return;
        }
        if (primary()) {
            CompositeLog.errorFix("element", "submit had no composite element", "Look the element up through ElementOperations so the hit stays on the adapter.");
        }
        raw.submit();
    }

    public static void clear(WebDriver driver, WebElement element) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) {
            composite.clear();
            return;
        }
        if (primary()) {
            CompositeLog.errorFix("element", "clear had no composite element", "Look the element up through ElementOperations so the hit stays on the adapter.");
        }
        raw.clear();
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

    /**
     * A press of letters and digits. Composite {@code type} is called with {@code append}
     * so the existing value stays. {@link #sendKeys} still replaces.
     */
    public static void appendKeys(WebDriver driver, WebElement element, CharSequence... keys) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) {
            DomCompositeAdapter.get().type(composite, text(keys), true);
            return;
        }
        sendKeys(driver, element, keys);
    }

    public static void doubleClick(WebDriver driver, WebElement element) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) {
            DomCompositeAdapter.get().doubleClick(composite);
            return;
        }
        click(driver, element);
        click(driver, element);
    }

    public static void contextClick(WebDriver driver, WebElement element) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) {
            DomCompositeAdapter.get().contextClick(composite);
            return;
        }
        click(driver, element);
    }

    /**
     * Sends composite {@code key} chords to a composite element.
     * False when this run is not on composite, the hit is not a composite element,
     * the expression is not a chord the verb can represent, or {@code key} is not listed.
     * A true result means every chord was one {@code key} command and the caller must not also use Actions.
     */
    public static boolean pressKeys(WebDriver driver, WebElement element, String input) {
        DomCompositeAdapter adapter = DomCompositeAdapter.get();
        if (!primary() && !adapter.dryRun()) return false;
        WebElement raw = unwrap(element);
        if (!(raw instanceof CompositeWebElement composite)) return false;
        if (composite.selector() != null && "desktop".equalsIgnoreCase(composite.selector().source())) return false;
        List<String> chords = KeyParser.compositeChords(input);
        if (chords == null || chords.isEmpty()) return false;
        if (!adapter.actionListed("key")) return false;
        for (String chord : chords) {
            if (!adapter.key(composite, chord)) return false;
        }
        return true;
    }

    public static void hover(WebDriver driver, WebElement element) {
        WebElement raw = unwrap(element);
        if (raw instanceof CompositeWebElement composite) {
            DomCompositeAdapter.get().hover(driver, composite);
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

    /** One batched read. Composite copies the query payload. Selenium runs one script. */
    public static List<ElementView> describe(WebDriver driver, By by) {
        return ElementReads.describe(driver, by);
    }

    /**
     * One engine query. Composite forwards the selector string. Selenium runs a
     * single css= or xpath= stage. A richer query with composite off fails closed.
     */
    public static List<ElementView> describe(WebDriver driver, EngineQuery query) {
        if (query == null || query.text().isBlank()) return List.of();
        if (CompositeRuntime.primary()) {
            List<WebElement> found = DomCompositeAdapter.get().findEngine(query);
            return ElementReads.describe(driver, found);
        }
        By fallback = query.seleniumFallback();
        if (fallback == null) {
            throw new IllegalStateException(
                    "engine query needs the composite runtime: " + query.text()
            );
        }
        CompositeLog.info("query", "selenium fallback " + query.text());
        return describe(driver, fallback);
    }

    /** Click or type through the same query object. Composite only. */
    public static ElementView act(EngineQuery query, String verb, String typed) {
        if (query == null || query.text().isBlank()) {
            throw new IllegalArgumentException("engine query must not be blank");
        }
        if (!CompositeRuntime.primary()) {
            throw new IllegalStateException("engine action needs the composite runtime");
        }
        EngineQuery used = query.exposure() == null ? query.exposure("human") : query;
        return DomCompositeAdapter.get().act(used, verb, typed);
    }

    /** Describe elements already found. Composite hits do not take another round trip. */
    public static List<ElementView> describe(WebDriver driver, List<WebElement> elements) {
        return ElementReads.describe(driver, elements);
    }

    public static int batchCallsForTests() {
        return ElementReads.BATCHES.get();
    }

    public static void resetBatchCallsForTests() {
        ElementReads.BATCHES.set(0);
    }

    /** Finds since the last {@link #resetFindCallsForTests()}. State checks should add zero. */
    public static int findCallsForTests() {
        return FINDS.get();
    }

    public static void resetFindCallsForTests() {
        FINDS.set(0);
    }

    public static int stateFallbacksForTests() {
        return STATE_FALLBACKS.get();
    }

    public static void resetStateMeasurementsForTests() {
        FINDS.set(0);
        STATE_FALLBACKS.set(0);
        ElementStateCache.resetForTests();
    }

    public static void noteStateFallbackForTests() {
        STATE_FALLBACKS.incrementAndGet();
    }

    public static Actions actions(WebDriver driver) {
        return new Actions(driver);
    }

    public static Object script(WebDriver driver, String source, Object... args) {
        if (!(driver instanceof JavascriptExecutor javascript)) {
            throw new IllegalArgumentException("error: this driver cannot run JavaScript. fix: use a browser driver.");
        }
        return javascript.executeScript(source, args == null ? new Object[0] : args);
    }

    /** ElementWrapper keeps stale retry. Every other element is one driver script. */
    public static Object scriptOn(WebDriver driver, WebElement element, String source, Object... extra) {
        if (element instanceof ElementWrapper wrapper) return wrapper.executeScript(source, extra);
        Object[] more = extra == null ? new Object[0] : extra;
        Object[] args = new Object[more.length + 1];
        args[0] = unwrap(element);
        System.arraycopy(more, 0, args, 1, more.length);
        return script(driver, source, args);
    }

    public static Object asyncScript(WebDriver driver, String source, Object... args) {
        if (!(driver instanceof JavascriptExecutor javascript)) {
            throw new IllegalArgumentException("error: this driver cannot run JavaScript. fix: use a browser driver.");
        }
        return javascript.executeAsyncScript(source, args == null ? new Object[0] : args);
    }

    public static void enterFrame(WebDriver driver, int index) {
        driver.switchTo().frame(index);
    }

    public static void defaultContent(WebDriver driver) {
        DomCompositeAdapter.get().clearFrames();
        if (driver != null) driver.switchTo().defaultContent();
    }

    public static void parentFrame(WebDriver driver) {
        DomCompositeAdapter.get().popFrame();
        if (driver != null) driver.switchTo().parentFrame();
    }

    /** Full document HTML. There is no composite page-source verb, so this is the one Selenium call. */
    public static String pageSource(WebDriver driver) {
        return driver.getPageSource();
    }

    public static String text(WebElement element) {
        return ElementReads.text(element);
    }

    public static String textContent(WebDriver driver, WebElement element) {
        return ElementReads.textContent(driver, element);
    }

    public static String tag(WebElement element) {
        return ElementReads.tag(element);
    }

    public static boolean displayed(WebElement element) {
        return ElementReads.displayed(element);
    }

    public static boolean enabled(WebElement element) {
        return ElementReads.enabled(element);
    }

    public static boolean selected(WebElement element) {
        return ElementReads.selected(element);
    }

    public static String attribute(WebElement element, String name) {
        return ElementReads.attribute(element, name);
    }

    public static String domAttribute(WebElement element, String name) {
        return ElementReads.domAttribute(element, name);
    }

    public static String domProperty(WebElement element, String name) {
        return ElementReads.domProperty(element, name);
    }

    public static String css(WebElement element, String property) {
        return ElementReads.css(element, property);
    }

    public static Rectangle rect(WebElement element) {
        return ElementReads.rect(element);
    }

    public static Point location(WebElement element) {
        Rectangle bounds = rect(element);
        return bounds == null ? new Point(0, 0) : bounds.getPoint();
    }

    public static Dimension size(WebElement element) {
        Rectangle bounds = rect(element);
        return bounds == null ? new Dimension(0, 0) : bounds.getDimension();
    }

    public static int optionCount(WebElement element) {
        return new Select(unwrap(element)).getOptions().size();
    }

    public static void selectByIndex(WebElement element, int index) {
        new Select(unwrap(element)).selectByIndex(index);
    }

    public static void selectMatching(WebElement select, WebElement option, String visibleText) {
        Select sel = new Select(unwrap(select));
        WebElement wanted = unwrap(option);
        List<WebElement> options = sel.getOptions();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equals(wanted)) {
                sel.selectByIndex(i);
                return;
            }
        }
        sel.selectByVisibleText(visibleText);
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
