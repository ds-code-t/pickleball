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
import tools.dscode.browser.BrowserFacade;
import tools.dscode.browser.BrowserLog;
import tools.dscode.browser.ProtocolSelector;
import tools.dscode.browser.SeleniumAdapter;
import tools.dscode.common.domoperations.KeyParser;
import tools.dscode.common.seleniumextensions.ElementWrapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single entry for element lookup and actions. {@code pkb_composite=on} uses the
 * Java engine. Unset, {@code auto}, and {@code off} use {@link SeleniumAdapter}
 * and do not call a direct protocol.
 */
public final class ElementOperations {
    private static final AtomicInteger FINDS = new AtomicInteger();
    private static final AtomicInteger STATE_FALLBACKS = new AtomicInteger();

    /**
     * Selenium cannot report the frame it is in. Notes record only frames entered
     * through this class, so a step can restore the context it started in.
     * {@code selenium} is false for a composite frame, which does not switch the driver.
     */
    private record FrameNote(String name, boolean selenium) {
    }

    private static final ThreadLocal<List<FrameNote>> FRAMES = ThreadLocal.withInitial(ArrayList::new);

    private ElementOperations() {
    }

    /** Depth of frames entered on this thread. A step marks this before it runs. */
    public static int frameDepth() {
        return FRAMES.get().size();
    }

    /** Names of frames entered after {@code depth}, in order. Blank names are omitted. */
    public static String framesEnteredSince(int depth) {
        List<FrameNote> stack = FRAMES.get();
        if (depth < 0 || depth >= stack.size()) return "";
        StringBuilder joined = new StringBuilder();
        for (int index = depth; index < stack.size(); index++) {
            String name = stack.get(index).name;
            if (name == null || name.isBlank()) continue;
            if (!joined.isEmpty()) joined.append(' ');
            joined.append(name);
        }
        return joined.toString();
    }

    /**
     * Snapshot of the notes. {@link tools.dscode.common.seleniumextensions.AllContextsScan}
     * rewinds to this after it puts the driver back, so its own switches are not frames the step entered.
     */
    public static Object frameMark() {
        return List.copyOf(FRAMES.get());
    }

    public static void rewindFrameMark(Object mark) {
        ArrayList<FrameNote> copy = new ArrayList<>();
        if (mark instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof FrameNote note) copy.add(note);
            }
        }
        FRAMES.set(copy);
    }

    /** Pop frames this step pushed. A switch that throws at the top document goes back to default content. */
    public static void restoreFrames(WebDriver driver, int depth) {
        if (driver == null) return;
        if (depth < 0) depth = 0;
        int guard = 0;
        while (FRAMES.get().size() > depth && guard++ < 32) {
            FrameNote top = FRAMES.get().get(FRAMES.get().size() - 1);
            if (!top.selenium) {
                DomCompositeAdapter.get().popFrame();
                popNote();
                continue;
            }
            try {
                parentFrame(driver);
            } catch (RuntimeException thrown) {
                if (depth == 0) {
                    try {
                        defaultContent(driver);
                    } catch (RuntimeException ignored) {
                        FRAMES.get().clear();
                    }
                }
                return;
            }
        }
    }

    private static void note(String name, boolean selenium) {
        FRAMES.get().add(new FrameNote(name == null ? "" : name, selenium));
    }

    private static void popNote() {
        List<FrameNote> stack = FRAMES.get();
        if (!stack.isEmpty()) stack.remove(stack.size() - 1);
    }

    private static String frameLabel(WebElement raw) {
        if (raw == null) return "";
        for (String key : new String[]{"id", "name", "title"}) {
            try {
                String value = attribute(raw, key);
                if (value != null && !value.isBlank()) return value.trim();
            } catch (RuntimeException ignored) {
                return "";
            }
        }
        return "";
    }

    public static boolean primary() {
        return CompositeRuntime.primary();
    }

    /**
     * Scripted path. Always {@link SeleniumAdapter}. A direct adapter is refused.
     */
    public static BrowserFacade library(WebDriver driver) {
        BrowserFacade facade = ProtocolSelector.forDriver(driver, ProtocolSelector.Preference.LIBRARY);
        if (!(facade instanceof SeleniumAdapter) || !"selenium".equals(facade.protocol())) {
            String protocol = facade == null ? "none" : facade.protocol();
            BrowserLog.warnOnce("scripted-direct", "scripted ElementOperations refused " + protocol);
            throw new IllegalStateException(
                    "error: scripted ElementOperations cannot use " + protocol + ".\n"
                            + "fix: Direct protocols go through AgentProtocolController. "
                            + "pkb_composite=on uses the Java engine.");
        }
        return facade;
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
        if (context instanceof WebDriver driver && !primary()) library(driver);
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
        String name = frameLabel(raw);
        if (raw instanceof CompositeWebElement composite) {
            composite.enterFrame();
            note(name, false);
            return;
        }
        driver.switchTo().frame(raw);
        note(name, true);
    }

    public static SearchContext shadowRoot(WebElement host) {
        return unwrap(host).getShadowRoot();
    }

    public static void click(WebDriver driver, WebElement element) {
        WebElement raw = unwrap(element);
        tools.dscode.common.domoperations.MaskQuiet.await(driver, raw == null ? element : raw, 3_000L);
        if (raw instanceof CompositeWebElement composite) {
            composite.click();
            return;
        }
        if (primary()) {
            CompositeLog.errorFix("element", "click had no composite element", "Look the element up through ElementOperations so the hit stays on the adapter.");
        } else if (driver != null) {
            library(driver);
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
            if (driver != null && !primary()) library(driver);
            new Actions(driver).sendKeys(keys).perform();
            return;
        }
        if (driver != null && !primary()) library(driver);
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
            List<ElementView> snapped = snapshotQuery(driver, query);
            if (snapped != null) {
                CompositeLog.info("query", "snapshot " + query.text());
                return snapped;
            }
            throw new IllegalStateException(
                    "engine query needs the composite runtime: " + query.text()
            );
        }
        CompositeLog.info("query", "selenium fallback " + query.text());
        return describe(driver, fallback);
    }

    /**
     * text= and role= on a scriptable driver read the top document once.
     * That read does not walk shadow roots or iframes.
     * near=, below=, and above=, and a driver that cannot run a script, use the page source.
     * Source matches take displayed and enabled from attributes. They do not invent a layout box.
     * Null when this query is not one of those selectors or the page cannot be read.
     */
    private static final String LIVE_TEXT_ROLE = """
            var kind = arguments[0];
            var wanted = arguments[1];
            function norm(value) {
              return String(value == null ? '' : value).replace(/\\s+/g, ' ').trim();
            }
            function ownText(el) {
              var direct = '';
              var nodes = el.childNodes || [];
              for (var i = 0; i < nodes.length; i++) {
                if (nodes[i].nodeType === 3) direct += nodes[i].nodeValue || '';
              }
              direct = norm(direct);
              if (direct) return direct;
              return norm(el.textContent);
            }
            function shown(el) {
              if (el.hidden) return false;
              var style = window.getComputedStyle(el);
              if (!style || style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
              var box = el.getBoundingClientRect();
              return box.width >= 1 && box.height >= 1;
            }
            function on(el) {
              if (el.disabled) return false;
              return el.getAttribute('aria-disabled') !== 'true';
            }
            var out = [];
            var all = document.querySelectorAll('body *');
            for (var i = 0; i < all.length; i++) {
              var el = all[i];
              var text = ownText(el);
              var label = norm(el.getAttribute('aria-label'));
              var role = norm(el.getAttribute('role'));
              if (!role) role = el.tagName.toLowerCase();
              var hit = kind === 'role' ? role === wanted : (text === wanted || label === wanted);
              if (!hit) continue;
              var box = el.getBoundingClientRect();
              out.push({
                tag: el.tagName.toLowerCase(),
                text: text || label,
                displayed: shown(el),
                enabled: on(el),
                id: el.id || '',
                x: Math.round(box.x),
                y: Math.round(box.y),
                width: Math.round(box.width),
                height: Math.round(box.height)
              });
            }
            return out;
            """;

    private static List<ElementView> snapshotQuery(WebDriver driver, EngineQuery query) {
        if (driver == null || query == null || query.text() == null || query.text().isBlank()) return null;
        String pipeline = query.text().trim();
        if (!pipeline.startsWith("text=") && !pipeline.startsWith("role=")
                && !pipeline.startsWith("near=") && !pipeline.startsWith("below=")
                && !pipeline.startsWith("above=")) {
            return null;
        }
        String[] simple = simpleTextOrRole(pipeline);
        if (simple != null && driver instanceof JavascriptExecutor) {
            try {
                List<ElementView> live = liveTextOrRole(driver, simple[0], simple[1]);
                if (live != null) return live;
            } catch (RuntimeException ignored) {
                // The page source still answers when the script cannot run.
            }
        }
        return sourceQuery(driver, pipeline);
    }

    /** A bare text= or role= value. Null when the pipeline has another stage. */
    private static String[] simpleTextOrRole(String pipeline) {
        int eq = pipeline.indexOf('=');
        if (eq <= 0) return null;
        String kind = pipeline.substring(0, eq).trim();
        if (!"text".equals(kind) && !"role".equals(kind)) return null;
        String rest = pipeline.substring(eq + 1).trim();
        if (rest.contains(">>") || rest.contains(" AND ") || rest.contains(" OR ") || rest.contains(" NOT ")) return null;
        if (rest.length() >= 2
                && ((rest.startsWith("\"") && rest.endsWith("\"")) || (rest.startsWith("'") && rest.endsWith("'")))) {
            rest = rest.substring(1, rest.length() - 1);
        }
        return new String[]{kind, rest.replaceAll("\\s+", " ").trim()};
    }

    private static List<ElementView> liveTextOrRole(WebDriver driver, String kind, String wanted) {
        Object raw = script(driver, LIVE_TEXT_ROLE, kind, wanted);
        if (!(raw instanceof List<?> rows)) return null;
        java.util.ArrayList<ElementView> views = new java.util.ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof java.util.Map<?, ?> map)) continue;
            String tag = map.get("tag") == null ? "" : String.valueOf(map.get("tag"));
            String text = map.get("text") == null ? "" : String.valueOf(map.get("text"));
            boolean displayed = flag(map.get("displayed"));
            boolean enabled = flag(map.get("enabled"));
            String id = map.get("id") == null ? "" : String.valueOf(map.get("id"));
            views.add(new ElementView(
                    tag,
                    text,
                    displayed,
                    enabled,
                    false,
                    id.isBlank() ? java.util.Map.of() : java.util.Map.of("id", id),
                    number(map.get("x")),
                    number(map.get("y")),
                    number(map.get("width")),
                    number(map.get("height")),
                    "",
                    "",
                    new ElementState(!enabled, text.isBlank(), false, false, false, false, false)
            ));
        }
        return views;
    }

    private static boolean flag(Object value) {
        if (value instanceof Boolean on) return on;
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static int number(Object value) {
        if (value instanceof Number n) return (int) Math.round(n.doubleValue());
        return 0;
    }

    private static List<ElementView> sourceQuery(WebDriver driver, String pipeline) {
        try {
            dev.domengine.tree.DomNode root = dev.domengine.query.HtmlTree.parse(driver.getPageSource());
            com.fasterxml.jackson.databind.node.ObjectNode found =
                    dev.domengine.query.Query.run(root, java.util.Map.of("pipeline", pipeline));
            com.fasterxml.jackson.databind.JsonNode nodes = found.get("nodes");
            java.util.ArrayList<ElementView> views = new java.util.ArrayList<>();
            if (nodes != null) {
                for (com.fasterxml.jackson.databind.JsonNode node : nodes) {
                    String text = node.path("text").asText("");
                    String tag = node.path("node_name").asText("").toLowerCase(java.util.Locale.ROOT);
                    com.fasterxml.jackson.databind.JsonNode attrs = node.path("attributes");
                    boolean displayed = sourceDisplayed(attrs);
                    boolean enabled = sourceEnabled(attrs);
                    String id = attrs.path("id").asText("");
                    views.add(new ElementView(
                            tag,
                            text,
                            displayed,
                            enabled,
                            false,
                            id.isBlank() ? java.util.Map.of() : java.util.Map.of("id", id),
                            0,
                            0,
                            0,
                            0,
                            "",
                            "",
                            new ElementState(!enabled, text.isBlank(), false, false, false, false, false)
                    ));
                }
            }
            return views;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** Page source has no layout. Hidden, aria-hidden, and a hiding style are not displayed. */
    private static boolean sourceDisplayed(com.fasterxml.jackson.databind.JsonNode attrs) {
        if (attrs.has("hidden")) return false;
        if ("true".equalsIgnoreCase(attrs.path("aria-hidden").asText(""))) return false;
        String style = attrs.path("style").asText("").toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        return !style.contains("display:none") && !style.contains("visibility:hidden");
    }

    private static boolean sourceEnabled(com.fasterxml.jackson.databind.JsonNode attrs) {
        if (attrs.has("disabled")) return false;
        return !"true".equalsIgnoreCase(attrs.path("aria-disabled").asText(""));
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
        note(Integer.toString(index), true);
    }

    public static void defaultContent(WebDriver driver) {
        DomCompositeAdapter.get().clearFrames();
        if (driver != null) driver.switchTo().defaultContent();
        FRAMES.get().clear();
    }

    public static void parentFrame(WebDriver driver) {
        DomCompositeAdapter.get().popFrame();
        if (driver != null) driver.switchTo().parentFrame();
        popNote();
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
