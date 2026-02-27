package tools.dscode.common.domoperations;

import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    public static List<WebElement> findWithRetry(SearchContext context, By locator, ElementMatch elementMatch) {

        boolean displayedElementsOnly =
                !elementMatch.categoryFlags.contains(ExecutionDictionary.CategoryFlags.NON_DISPLAY_ELEMENT);

        NestingMode mode = NestingMode.DEEPEST_ONLY;

        if (elementMatch.categoryFlags.contains(ExecutionDictionary.CategoryFlags.NO_NESTING_FILTER)) {
            mode = NestingMode.NONE;
        } else if (elementMatch.categoryFlags.contains(ExecutionDictionary.CategoryFlags.OUTER_NESTING_FILTER)) {
            mode = NestingMode.OUTERMOST_ONLY;
        }

        return findWithRetry(context, locator, Duration.ofSeconds(10), displayedElementsOnly, mode);
    }

    public static List<WebElement> findWithRetry(SearchContext context, By locator, Duration timeout, boolean displayedElementsOnly, NestingMode mode) {
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

        throw last != null ? last
                : new StaleElementReferenceException("Timed out retrying find due to stale elements");
    }

    public static List<WebElement> find(SearchContext context, By locator, boolean displayedElementsOnly, NestingMode mode) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(mode, "mode");

        List<WebElement> matches = context.findElements(locator);

        if (displayedElementsOnly) {
            matches = matches.stream().filter(WebElement::isDisplayed).toList();
        }

        printDebug("##elements-find-matches.size(): " + matches.size());

        if (mode == NestingMode.NONE || matches.size() <= 1) return matches;

        By within = withinElementLocator(locator);

        List<WebElement> out = new ArrayList<>(matches.size());

        if (mode == NestingMode.DEEPEST_ONLY) {
            // Drop el if it contains any other match in its subtree.
            for (WebElement el : matches) {
                List<WebElement> internal = el.findElements(within);

                boolean containsOtherMatch = false;
                for (WebElement inner : internal) {
                    if (inner.equals(el)) continue;

                    for (WebElement other : matches) {
                        if (other.equals(el)) continue;
                        if (inner.equals(other)) {
                            containsOtherMatch = true;
                            break;
                        }
                    }
                    if (containsOtherMatch) break;
                }

                if (!containsOtherMatch) out.add(el);
            }

        } else if (mode == NestingMode.OUTERMOST_ONLY) {
            // Drop el if it is contained by any other match (i.e., appears in other's subtree).
            for (WebElement el : matches) {
                boolean isContainedByOtherMatch = false;

                for (WebElement other : matches) {
                    if (other.equals(el)) continue;

                    List<WebElement> internal = other.findElements(within);
                    for (WebElement inner : internal) {
                        if (inner.equals(other)) continue;
                        if (inner.equals(el)) {
                            isContainedByOtherMatch = true;
                            break;
                        }
                    }
                    if (isContainedByOtherMatch) break;
                }

                if (!isContainedByOtherMatch) out.add(el);
            }
        }

        printDebug("##elements-find-out.size(): " + out.size());
        return out;
    }

    /**
     * Best-effort conversion so el.findElements(within) searches within el's subtree.
     * - XPath "//..." or "/..." becomes ".//..." (may include self; we ignore self via equals check)
     * - Non-XPath locators are already scoped under WebElement.findElements.
     */
    public static By withinElementLocator(By locator) {
        String s = locator.toString();
        String prefix = "By.xpath: ";
        if (!s.startsWith(prefix)) return locator;

        String xp = s.substring(prefix.length()).trim();
        if (xp.startsWith("//")) xp = ".//" + xp.substring(2);
        else if (xp.startsWith("/")) xp = ".//" + xp.substring(1);

        return By.xpath(xp);
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