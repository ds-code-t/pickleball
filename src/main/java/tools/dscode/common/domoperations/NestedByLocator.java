package tools.dscode.common.domoperations;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WrapsDriver;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.common.composite.CompositeWebElement;
import tools.dscode.common.composite.ElementView;
import tools.dscode.common.dataoperations.TextOp;
import tools.dscode.common.dataoperations.TextPredicateMatcher;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;

public final class NestedByLocator {

    private NestedByLocator() {}

    public enum NestingMode {
        /** Keep only elements that do NOT contain any other element from the same matches list. */
        DEEPEST_ONLY,

        /** Keep only elements that are NOT contained by any other element from the same matches list. */
        OUTERMOST_ONLY,

        /** No nesting-based filtering. */
        NONE
    }

    /**
     * Browser-side containment analysis for already-found elements.
     *
     * This is the main optimization:
     * - avoids one nested findElements(...) call per matched element
     * - avoids repeated selector evaluation inside each subtree
     * - computes the keep/drop flags in one browser-side pass
     *
     * The script intentionally does NOT rely on match index ordering for correctness.
     * It uses DOM contains(...) directly, which is simple and safe.
     */
    private static final String JS_FILTER_BY_NESTING = """
        const els = arguments[0];
        const mode = arguments[1];
        const n = els.length;
        const keep = new Array(n).fill(true);

        if (mode === 'DEEPEST_ONLY') {
            for (let i = 0; i < n; i++) {
                const outer = els[i];
                for (let j = 0; j < n; j++) {
                    if (i === j) continue;
                    const inner = els[j];
                    if (outer !== inner && outer.contains(inner)) {
                        keep[i] = false;
                        break;
                    }
                }
            }
            return keep;
        }

        if (mode === 'OUTERMOST_ONLY') {
            for (let i = 0; i < n; i++) {
                const inner = els[i];
                for (let j = 0; j < n; j++) {
                    if (i === j) continue;
                    const outer = els[j];
                    if (outer !== inner && outer.contains(inner)) {
                        keep[i] = false;
                        break;
                    }
                }
            }
            return keep;
        }

        return keep;
        """;

    public static List<WebElement> findWithRetry(SearchContext context, By locator, ElementMatch elementMatch) {

        boolean displayedElementsOnly =
                !elementMatch.categoryFlags.contains(ExecutionDictionary.CategoryFlags.NON_DISPLAY_ELEMENT);

        NestingMode mode = NestingMode.DEEPEST_ONLY;

        if (elementMatch.categoryFlags.contains(ExecutionDictionary.CategoryFlags.NO_NESTING_FILTER)) {
            mode = NestingMode.NONE;
        } else if (elementMatch.categoryFlags.contains(ExecutionDictionary.CategoryFlags.OUTER_NESTING_FILTER)) {
            mode = NestingMode.OUTERMOST_ONLY;
        }

        List<WebElement> returnList;
        PositionSelection.EnginePosition position = PositionSelection.enginePosition(elementMatch);
        boolean regex = PositionSelection.regexBeforePosition(elementMatch);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        StaleElementReferenceException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                Found found = findCollected(
                        context, locator, displayedElementsOnly, mode, regex ? null : position);
                if (found.positioned()) {
                    logTrace("elements-find-out.size(): " + found.elements().size());
                    return found.elements();
                }
                returnList = retainFullMatches(found.elements(), elementMatch);
                if (position != null && tools.dscode.common.composite.ElementOperations.primary()) {
                    CompositeLog.info("adapter", "position java");
                }
                return PositionSelection.apply(returnList, elementMatch);
            } catch (StaleElementReferenceException e) {
                last = e;
                sleep(Duration.ofSeconds(3));
            }
        }
        throw last != null
                ? last
                : new StaleElementReferenceException("Timed out retrying find due to stale elements");
    }

    public static List<WebElement> retainFullMatches(List<WebElement> found, ElementMatch elementMatch) {
        if (found == null || found.isEmpty() || elementMatch == null || elementMatch.textOps == null) {
            return found == null ? new ArrayList<>() : found;
        }
        List<ElementMatch.TextOp> regexOps = new ArrayList<>();
        for (ElementMatch.TextOp textOp : elementMatch.textOps) {
            if (textOp.op() == ExecutionDictionary.Op.MATCHES) {
                regexOps.add(textOp);
            }
        }
        if (regexOps.isEmpty()) {
            return found;
        }
        JavascriptExecutor javascript = getJavascriptExecutor(null, found);
        List<WebElement> matched = new ArrayList<>();
        for (WebElement element : found) {
            String text;
            try {
                text = matchText(javascript, element);
            } catch (StaleElementReferenceException ignored) {
                continue;
            }
            boolean kept = true;
            for (ElementMatch.TextOp textOp : regexOps) {
                if (!TextPredicateMatcher.matches(
                        text == null ? "" : text,
                        new TextOp(textOp.text(), textOp.op())
                )) {
                    kept = false;
                    break;
                }
            }
            if (kept) {
                matched.add(element);
            }
        }
        return matched;
    }

    /**
     * The label a person reads. {@code textContent} keeps "Save All" intact.
     * Selenium {@code getText()} can return an empty string for that button.
     */
    private static String matchText(JavascriptExecutor javascript, WebElement element) {
        WebDriver driver = javascript instanceof WebDriver web ? web : null;
        return tools.dscode.common.composite.ElementOperations.textContent(driver, element);
    }

    public static <T> ArrayList<T> sampleEvery(List<T> list, int startIndex, int step) {
        return PositionSelection.every(list, startIndex, step);
    }

    public static List<WebElement> findWithRetry(
            SearchContext context,
            By locator,
            Duration timeout,
            boolean displayedElementsOnly,
            NestingMode mode
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(mode, "mode");

        Instant deadline = Instant.now().plus(timeout);
        StaleElementReferenceException last = null;

        while (Instant.now().isBefore(deadline)) {
            try {
                return find(context, locator, displayedElementsOnly, mode);
            } catch (StaleElementReferenceException e) {
                last = e;
                sleep(Duration.ofSeconds(3));
            }
        }

        throw last != null
                ? last
                : new StaleElementReferenceException("Timed out retrying find due to stale elements");
    }

    public static List<WebElement> find(
            SearchContext context,
            By locator,
            boolean displayedElementsOnly,
            NestingMode mode
    ) {
        return findCollected(context, locator, displayedElementsOnly, mode, null).elements();
    }

    /**
     * One query carries {@code --nesting} after {@code displayed}, then {@code --nth}.
     * Position stays in Java when a regex still has to run, or the binary has no nth flag.
     */
    private record Found(List<WebElement> elements, boolean positioned) {
    }

    private static Found findCollected(
            SearchContext context,
            By locator,
            boolean displayedElementsOnly,
            NestingMode mode,
            PositionSelection.EnginePosition position
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(mode, "mode");

        if (engineNest(context, mode)) {
            tools.dscode.common.composite.DomCompositeAdapter adapter =
                    tools.dscode.common.composite.DomCompositeAdapter.get();
            tools.dscode.common.domoperations.PositionSelection.EnginePosition send =
                    canSend(adapter, position) ? position : null;
            String nesting = mode == NestingMode.OUTERMOST_ONLY ? "outermost" : "deepest";
            String within = null;
            if (context instanceof tools.dscode.common.composite.CompositeWebElement composite) {
                within = composite.nodeId();
            }
            String filter = displayedElementsOnly ? "displayed" : null;
            List<WebElement> matches = adapter.findScoped(locator, within, nesting, filter, send);
            logTrace("elements-find-out.size(): " + matches.size());
            return new Found(matches, send != null);
        }

        if (position != null && mode == NestingMode.NONE && !displayedElementsOnly && plainContext(context)) {
            tools.dscode.common.composite.DomCompositeAdapter adapter =
                    tools.dscode.common.composite.DomCompositeAdapter.get();
            if (canSend(adapter, position)) {
                String within = null;
                if (context instanceof CompositeWebElement composite) within = composite.nodeId();
                List<WebElement> matches = adapter.findScoped(locator, within, null, null, position);
                logTrace("elements-find-out.size(): " + matches.size());
                return new Found(matches, true);
            }
        }

        List<WebElement> matches = tools.dscode.common.composite.ElementOperations.findElements(context, locator);

        if (displayedElementsOnly) {
            matches = filterDisplayed(context, matches);
        }

        logTrace("elements-find-matches.size(): " + matches.size());

        if (mode == NestingMode.NONE || matches.size() <= 1) {
            return new Found(matches, false);
        }

        List<WebElement> batched = tryCompositeNestBatch(matches, mode);
        if (batched != null) {
            logTrace("elements-find-out.size(): " + batched.size());
            return new Found(batched, false);
        }

        // Main optimized path: do containment analysis once in the browser.
        List<WebElement> browserSide = tryBrowserSideNestingFilter(context, matches, mode);
        if (browserSide != null) {
            logTrace("elements-find-out.size(): " + browserSide.size());
            return new Found(browserSide, false);
        }

        // Safe fallback if no JavascriptExecutor can be obtained.
        By within = withinElementLocator(locator);

        List<WebElement> out = switch (mode) {
            case DEEPEST_ONLY -> deepestOnlyFallback(matches, within);
            case OUTERMOST_ONLY -> outermostOnlyFallback(matches, within);
            case NONE -> matches;
        };

        logTrace("elements-find-final-out.size(): " + out.size());
        return new Found(out, false);
    }

    private static boolean canSend(
            tools.dscode.common.composite.DomCompositeAdapter adapter,
            tools.dscode.common.domoperations.PositionSelection.EnginePosition position
    ) {
        if (position == null || !adapter.queryFlag("nth")) return false;
        return position.every() == null || adapter.queryFlag("every");
    }

    /** A driver or a composite node that can take {@code --within}. Not a raw Selenium element. */
    private static boolean plainContext(SearchContext context) {
        if (context == null || context instanceof WebDriver) return true;
        if (context instanceof CompositeWebElement composite) {
            return tools.dscode.common.composite.DomCompositeAdapter.get().queryFlag("within")
                    && composite.nodeId() != null
                    && !composite.nodeId().isBlank();
        }
        return false;
    }

    /**
     * One query carries {@code --nesting} after {@code displayed}.
     * An element context also needs {@code --within}. Otherwise the anchor walk stays.
     */
    private static boolean engineNest(SearchContext context, NestingMode mode) {
        if (mode != NestingMode.DEEPEST_ONLY && mode != NestingMode.OUTERMOST_ONLY) return false;
        tools.dscode.common.composite.DomCompositeAdapter adapter =
                tools.dscode.common.composite.DomCompositeAdapter.get();
        if (!tools.dscode.common.composite.ElementOperations.primary() || !adapter.queryFlag("nesting")) {
            if (tools.dscode.common.composite.ElementOperations.primary()) {
                CompositeLog.info("adapter", "nesting fallback");
            }
            return false;
        }
        // A driver that has not joined a browser is the page under test.
        // A process-only session is not that page, so the Selenium walk stays.
        if (context instanceof WebDriver && !tools.dscode.common.composite.CompositeRuntime.browserJoined()) {
            CompositeLog.info("adapter", "nesting fallback");
            return false;
        }
        if (context == null || context instanceof WebDriver) return true;
        if (context instanceof CompositeWebElement composite) {
            return adapter.queryFlag("within")
                    && composite.nodeId() != null
                    && !composite.nodeId().isBlank();
        }
        return false;
    }

    private static List<WebElement> filterDisplayed(SearchContext context, List<WebElement> matches) {
        if (matches == null || matches.isEmpty()) return matches == null ? List.of() : matches;
        WebDriver driver = context instanceof WebDriver web ? web : null;
        List<ElementView> views = tools.dscode.common.composite.ElementOperations.describe(driver, matches);
        List<WebElement> displayed = new ArrayList<>(matches.size());
        for (int i = 0; i < matches.size() && i < views.size(); i++) {
            if (views.get(i).displayed()) displayed.add(matches.get(i));
        }
        return displayed;
    }

    /**
     * One xpath union for composite hits that have an id or name anchor.
     * Null keeps the per-node ancestor walk.
     * Deepest drops containers. Outermost drops contained nodes.
     * An empty remainder keeps the original list.
     */
    static List<WebElement> tryCompositeNestBatch(List<WebElement> matches, NestingMode mode) {
        if (!compositeHits(matches)) return null;
        if (mode != NestingMode.DEEPEST_ONLY && mode != NestingMode.OUTERMOST_ONLY) return null;
        String axis = mode == NestingMode.DEEPEST_ONLY ? "/ancestor::*" : "/descendant::*";
        StringBuilder union = new StringBuilder();
        for (WebElement el : matches) {
            if (!(el instanceof CompositeWebElement composite)) return null;
            String anchor = composite.anchor();
            if (anchor == null) return null;
            if (!union.isEmpty()) union.append(" | ");
            union.append(anchor).append(axis);
        }
        CompositeLog.info("adapter", "nest batch");
        List<WebElement> related;
        try {
            related = tools.dscode.common.composite.ElementOperations.findElements(null, By.xpath(union.toString()));
        } catch (RuntimeException ignored) {
            return null;
        }
        Set<String> relatedIds = new HashSet<>();
        for (WebElement el : related) {
            if (el instanceof CompositeWebElement composite && composite.nodeId() != null) {
                relatedIds.add(composite.nodeId());
            }
        }
        List<WebElement> out = new ArrayList<>();
        for (WebElement el : matches) {
            CompositeWebElement composite = (CompositeWebElement) el;
            if (!relatedIds.contains(composite.nodeId())) out.add(el);
        }
        return out.isEmpty() ? List.copyOf(matches) : out;
    }

    /**
     * Returns filtered results using one browser-side execution, or null if
     * a JavascriptExecutor cannot be obtained or the returned structure is unexpected.
     */
    private static List<WebElement> tryBrowserSideNestingFilter(
            SearchContext context,
            List<WebElement> matches,
            NestingMode mode
    ) {
        JavascriptExecutor js = getJavascriptExecutor(context, matches);
        if (js == null || compositeHits(matches)) {
            return null;
        }

        Object raw;
        try {
            raw = tools.dscode.common.composite.ElementOperations.script(
                    (WebDriver) js, JS_FILTER_BY_NESTING, matches, mode.name());
        } catch (RuntimeException ignored) {
            // Composite hits are not Selenium elements. The Java fallback filters those.
            return null;
        }

        if (!(raw instanceof List<?> flags) || flags.size() != matches.size()) {
            return null;
        }

        List<WebElement> out = new ArrayList<>(matches.size());
        for (int i = 0; i < matches.size(); i++) {
            if (Boolean.TRUE.equals(flags.get(i))) {
                out.add(matches.get(i));
            }
        }
        return out;
    }

    /**
     * Safe fallback for DEEPEST_ONLY.
     *
     * Only computes what DEEPEST_ONLY needs:
     * whether each element contains any other original match.
     * Breaks as soon as the answer becomes true for that element.
     */
    private static List<WebElement> deepestOnlyFallback(List<WebElement> matches, By within) {
        if (compositeHits(matches)) return compositeDeepest(matches);
        Set<WebElement> matchSet = new HashSet<>(matches);
        List<WebElement> out = new ArrayList<>(matches.size());

        for (WebElement outer : matches) {
            boolean containsAnotherMatch = false;

            List<WebElement> internal = tools.dscode.common.composite.ElementOperations.findElements(outer, within);
            for (WebElement inner : internal) {
                if (inner.equals(outer)) continue;
                if (!matchSet.contains(inner)) continue;

                containsAnotherMatch = true;
                break;
            }

            if (!containsAnotherMatch) {
                out.add(outer);
            }
        }

        return out;
    }

    /**
     * Safe fallback for OUTERMOST_ONLY.
     *
     * Only computes what OUTERMOST_ONLY needs:
     * which elements are contained by at least one other original match.
     * Also stops early once only one possible outermost element remains.
     */
    private static List<WebElement> outermostOnlyFallback(List<WebElement> matches, By within) {
        if (compositeHits(matches)) return compositeOutermost(matches);
        Set<WebElement> matchSet = new HashSet<>(matches);
        Set<WebElement> containedByAnotherMatch = new HashSet<>();
        int remainingPossibleOutermost = matches.size();

        for (WebElement outer : matches) {
            if (remainingPossibleOutermost <= 1) {
                break;
            }

            List<WebElement> internal = tools.dscode.common.composite.ElementOperations.findElements(outer, within);
            for (WebElement inner : internal) {
                if (inner.equals(outer)) continue;
                if (!matchSet.contains(inner)) continue;

                if (containedByAnotherMatch.add(inner)) {
                    remainingPossibleOutermost--;
                    if (remainingPossibleOutermost <= 1) {
                        break;
                    }
                }
            }
        }

        List<WebElement> out = new ArrayList<>(Math.max(remainingPossibleOutermost, 1));
        for (WebElement el : matches) {
            if (!containedByAnotherMatch.contains(el)) {
                out.add(el);
            }
        }

        return out;
    }

    private static boolean compositeHits(List<WebElement> matches) {
        for (WebElement el : matches) {
            if (el instanceof CompositeWebElement) return true;
        }
        return false;
    }

    /** Drop a match when one of the other matches is inside it. Keep the original list if that would drop all of them. */
    private static List<WebElement> compositeDeepest(List<WebElement> matches) {
        Set<WebElement> containers = compositeContainers(matches);
        if (containers.isEmpty()) return matches;
        List<WebElement> out = new ArrayList<>();
        for (WebElement el : matches) {
            if (!containers.contains(el)) out.add(el);
        }
        return out.isEmpty() ? matches : out;
    }

    /** Drop a match when it sits inside another match. */
    private static List<WebElement> compositeOutermost(List<WebElement> matches) {
        Set<WebElement> contained = new HashSet<>();
        for (WebElement inner : matches) {
            if (!(inner instanceof CompositeWebElement composite)) continue;
            for (WebElement ancestor : composite.ancestorElements()) {
                if (!ancestor.equals(inner) && matches.contains(ancestor)) {
                    contained.add(inner);
                    break;
                }
            }
        }
        if (contained.isEmpty()) return matches;
        List<WebElement> out = new ArrayList<>();
        for (WebElement el : matches) {
            if (!contained.contains(el)) out.add(el);
        }
        return out.isEmpty() ? matches : out;
    }

    private static Set<WebElement> compositeContainers(List<WebElement> matches) {
        Set<WebElement> containers = new HashSet<>();
        for (WebElement inner : matches) {
            if (!(inner instanceof CompositeWebElement composite)) continue;
            for (WebElement ancestor : composite.ancestorElements()) {
                if (!ancestor.equals(inner) && matches.contains(ancestor)) containers.add(ancestor);
            }
        }
        return containers;
    }

    /**
     * Best-effort conversion so el.findElements(within) searches within el's subtree.
     *
     * Examples:
     *   //div[...]     -> .//div[...]
     *   (//div[...])   -> (.//div[...])
     *   /div[...]      -> .//div[...]
     *   (/div[...])    -> (.//div[...])
     *
     * Non-XPath locators are already scoped under WebElement.findElements(...).
     */
    public static By withinElementLocator(By locator) {
        String s = locator.toString();
        String prefix = "By.xpath: ";
        if (!s.startsWith(prefix)) return locator;

        String xp = s.substring(prefix.length()).trim();

        if (xp.startsWith("(//")) {
            xp = "(.//" + xp.substring(3);
        } else if (xp.startsWith("//")) {
            xp = ".//" + xp.substring(2);
        } else if (xp.startsWith("(/")) {
            xp = "(.//" + xp.substring(2);
        } else if (xp.startsWith("/")) {
            xp = ".//" + xp.substring(1);
        }

        return By.xpath(xp);
    }

    /**
     * Tries to obtain a JavascriptExecutor without changing the public API.
     *
     * SearchContext may be:
     * - a WebDriver that is already a JavascriptExecutor
     * - a WebElement/ShadowRoot/etc. whose wrapped driver can execute JS
     * - otherwise unavailable, in which case the caller falls back safely
     */
    private static JavascriptExecutor getJavascriptExecutor(SearchContext context, List<WebElement> matches) {
        if (context instanceof JavascriptExecutor js) {
            return js;
        }

        if (context instanceof WrapsDriver wrapsDriver
                && wrapsDriver.getWrappedDriver() instanceof JavascriptExecutor js) {
            return js;
        }

        for (WebElement el : matches) {
            if (el instanceof WrapsDriver wrapsDriver
                    && wrapsDriver.getWrappedDriver() instanceof JavascriptExecutor js) {
                return js;
            }
        }

        return null;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Retry sleep interrupted", ie);
        }
    }
}