package tools.dscode.common.domoperations;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WrapsDriver;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static tools.dscode.common.util.debug.DebugUtils.printDebug;

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

        List<WebElement>  returnList = findWithRetry(context, locator, Duration.ofSeconds(10), displayedElementsOnly, mode);

        if(elementMatch.elementPosition.equalsIgnoreCase("last"))
            return new ArrayList<>(List.of(returnList.getLast()));

        if(elementMatch.selectionType.isEmpty()) {
            if(elementMatch.elementIndex  > returnList.size())
                return new ArrayList<>();
            return new ArrayList<>(List.of(returnList.get(elementMatch.elementIndex-1)));
        }
        if(elementMatch.elementIndex ==1)
            return returnList;

        return sampleEvery(returnList, elementMatch.elementIndex-1, elementMatch.elementIndex);
    }

    public static <T> ArrayList<T> sampleEvery(List<T> list, int startIndex, int step) {
        ArrayList<T> out = new ArrayList<>();
        for (int i = startIndex; i < list.size(); i += step) out.add(list.get(i));
        return out;
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
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(mode, "mode");

        List<WebElement> matches = context.findElements(locator);

        if (displayedElementsOnly) {
            matches = filterDisplayed(matches);
        }

        printDebug("##elements-find-matches.size(): " + matches.size());

        if (mode == NestingMode.NONE || matches.size() <= 1) {
            return matches;
        }

        // Main optimized path: do containment analysis once in the browser.
        List<WebElement> browserSide = tryBrowserSideNestingFilter(context, matches, mode);
        if (browserSide != null) {
            printDebug("##elements-find-out.size(): " + browserSide.size());
            return browserSide;
        }

        // Safe fallback if no JavascriptExecutor can be obtained.
        By within = withinElementLocator(locator);

        List<WebElement> out = switch (mode) {
            case DEEPEST_ONLY -> deepestOnlyFallback(matches, within);
            case OUTERMOST_ONLY -> outermostOnlyFallback(matches, within);
            case NONE -> matches;
        };

        printDebug("##elements-find-out.size(): " + out.size());
        return out;
    }

    private static List<WebElement> filterDisplayed(List<WebElement> matches) {
        List<WebElement> displayed = new ArrayList<>(matches.size());
        for (WebElement el : matches) {
            if (el.isDisplayed()) {
                displayed.add(el);
            }
        }
        return displayed;
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
        if (js == null) {
            return null;
        }

        Object raw = js.executeScript(JS_FILTER_BY_NESTING, matches, mode.name());

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
        Set<WebElement> matchSet = new HashSet<>(matches);
        List<WebElement> out = new ArrayList<>(matches.size());

        for (WebElement outer : matches) {
            boolean containsAnotherMatch = false;

            List<WebElement> internal = outer.findElements(within);
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
        Set<WebElement> matchSet = new HashSet<>(matches);
        Set<WebElement> containedByAnotherMatch = new HashSet<>();
        int remainingPossibleOutermost = matches.size();

        for (WebElement outer : matches) {
            if (remainingPossibleOutermost <= 1) {
                break;
            }

            List<WebElement> internal = outer.findElements(within);
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