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

    private DeepestByLocator() {
    }

    public static List<WebElement> findDeepestWithRetry(
            SearchContext context,
            By locator
    ) {
        return findDeepestWithRetry(context, locator, Duration.ofSeconds(2));
    }


    /**
     * Calls {@link #findDeepest(SearchContext, By)} and retries every 3 seconds
     * if a StaleElementReferenceException occurs, until the timeout is reached.
     *
     * @param context search context (WebDriver, WebElement, ShadowRoot)
     * @param locator locator used to find elements
     * @param timeout maximum time to keep retrying
     * @return deepest elements
     * @throws StaleElementReferenceException if still failing after timeout
     */
    public static List<WebElement> findDeepestWithRetry(
            SearchContext context,
            By locator,
            Duration timeout
    ) {
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

        throw last != null
                ? last
                : new StaleElementReferenceException(
                "Timed out retrying findDeepest due to stale elements"
        );
    }

    /**
     * Finds all elements matching {@code locator} within {@code context},
     * then removes any element that contains another match of the same locator
     * inside it (i.e., has internal matches).
     * <p>
     * Preserves original order.
     */
    public static List<WebElement> findDeepest(
            SearchContext context,
            By locator
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(locator, "locator");

        List<WebElement> matches = context.findElements(locator);
        if (matches.size() <= 1) return matches;

        By within = withinElementLocator(locator);

        List<WebElement> out = new ArrayList<>(matches.size());
        for (WebElement el : matches) {
            List<WebElement> internal = el.findElements(within);
            System.out.println("@@internal: " + internal);
            System.out.println("@@internal.size: " + internal.size());
            boolean hasOtherMatchInside =
                    internal.size() > 1 ||
                            (internal.size() == 1 && !internal.get(0).equals(el));


            if (!hasOtherMatchInside) {
                out.add(el);
            }
        }
        System.out.println("@@out: " + out + "");
        System.out.println("@@out.size: " + out.size() + "");
        return out;
    }

    /**
     * Converts a global locator into one that, when used with el.findElements(...),
     * searches within the element subtree.
     * <p>
     * - XPath: best-effort conversion to relative XPath
     * - Non-XPath: returned unchanged (already scoped by WebElement.findElements)
     */
    public static By withinElementLocator(By locator) {
        String s = locator.toString();

        // Selenium's By#toString is typically "By.xpath: //div[@x]"
        String prefix = "By.xpath: ";
        if (!s.startsWith(prefix)) {
            return locator;
        }

        String xp = s.substring(prefix.length()).trim();

        if (xp.startsWith("//")) {
            xp = ".//" + xp.substring(2);
        } else if (xp.startsWith("/")) {
            xp = ".//" + xp.substring(1);
        }
        // axis-started (descendant::, child::, etc.) already work as-is

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
