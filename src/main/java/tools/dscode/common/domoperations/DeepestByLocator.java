package tools.dscode.common.domoperations;

import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DeepestByLocator {

    private DeepestByLocator() {}

    public static List<WebElement> findDeepestWithRetry(SearchContext context, By locator) {
        return findDeepestWithRetry(context, locator, Duration.ofSeconds(10));
    }

    public static List<WebElement> findDeepestWithRetry(SearchContext context, By locator, Duration timeout) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(timeout, "timeout");

        Instant deadline = Instant.now().plus(timeout);
        StaleElementReferenceException last = null;

        while (Instant.now().isBefore(deadline)) {
            try {
                return findDeepest(context, locator);
            } catch (StaleElementReferenceException e) {
                last = e;
                sleep(Duration.ofSeconds(3));
            }
        }

        throw last != null ? last
                : new StaleElementReferenceException("Timed out retrying findDeepest due to stale elements");
    }

    /**
     * "Deepest" = keep only elements in matches that do NOT contain another element that is also in matches.
     *
     * Implementation:
     * - matches = context.findElements(locator)
     * - for each el in matches:
     *     internal = el.findElements(withinElementLocator(locator))   // descendants (and maybe self)
     *     if internal contains any element equal to some "other" in matches (other != el), drop el
     */
    public static List<WebElement> findDeepest(SearchContext context, By locator) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(locator, "locator");

        List<WebElement> matches = context.findElements(locator);



        if (matches.size() <= 1) return matches;

        By within = withinElementLocator(locator);

        List<WebElement> out = new ArrayList<>(matches.size());


        for (WebElement el : matches) {
            List<WebElement> internal = el.findElements(within);

            boolean containsOtherTopLevelMatch = false;

            // Look for any internal element that equals some other element in matches.
            for (WebElement inner : internal) {
                if (inner.equals(el)) continue; // ignore self if included

                for (WebElement other : matches) {
                    if (other.equals(el)) continue; // exclude current element
                    if (inner.equals(other)) {
                        containsOtherTopLevelMatch = true;
                        break;
                    }
                }
                if (containsOtherTopLevelMatch) break;
            }

            if (!containsOtherTopLevelMatch) {
                out.add(el);
            }
        }

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
