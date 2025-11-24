package tools.dscode.common.domoperations;

import org.openqa.selenium.*;
import java.util.ArrayList;
import java.util.List;

import static tools.dscode.common.util.DebugUtils.printDebug;

public class XPathChainResolver {

    /**
     * Entry point: resolve a chain of XPathData against the DOM.
     * Returns a flat list of elements from the final iteration.
     */
    public static List<WebElement> resolveXPathChain(
            WebDriver driver,
            List<XPathData> xPathDataList
    ) {
        if (xPathDataList == null || xPathDataList.isEmpty()) {
            return List.of();
        }

        // Start from the driver as the only SearchContext
        return resolveStep(driver, xPathDataList, 0, List.of(driver));
    }

    /**
     * Recursive step through XPathData list.
     *
     * @param driver          the WebDriver (needed for frame switching)
     * @param steps           list of XPathData
     * @param index           index of current step
     * @param currentContexts current SearchContexts to apply this step on
     */
    private static List<WebElement> resolveStep(
            WebDriver driver,
            List<XPathData> steps,
            int index,
            List<? extends SearchContext> currentContexts
    ) {
        XPathData current = steps.get(index);
        // 1. Find elements for current XPathData in all contexts
        List<WebElement> found = new ArrayList<>();
        for (SearchContext context : currentContexts) {
            found.addAll(context.findElements(current.xPathy().getLocator()));
        }

        // Deduplicate now
        found = found.stream().distinct().toList();
        System.out.println("Found " + found.size() + " elements for " + current);

        // If this is the last step, we're done: return what we found
        if (index == steps.size() - 1) {
            return found;
        }

        // Otherwise, we need to prepare contexts and/or recurse into frames/shadow for next steps
        if (found.isEmpty()) {
            return List.of(); // nothing to continue with
        }

        System.out.println("Attempting to find: " + current);
        if (current.isFrom()) {
            System.out.println("handleFromStep");
            // Special "from" semantics: only use the FIRST element in 'found'
            return handleFromStep(driver, steps, index, found);
        } else {
            System.out.println("resolveStep");
            // Simple case: next step runs relative to ALL elements found in this step
            return resolveStep(driver, steps, index + 1, found);
        }
    }

    /**
     * Special handling when current XPathData.isFrom == true.
     *
     * With the new semantics:
     * - Only the FIRST WebElement in 'found' is considered; others are discarded.
     * - If that element is an iframe/frame: we switch into it ONCE and do NOT switch back
     *   to defaultContent(), so the WebDriver remains in that frame context for the rest
     *   of the chain.
     * - If the element has a shadow root: run remaining steps inside that shadow root.
     * - Otherwise: run remaining steps with the element itself as the search context.
     */
    private static List<WebElement> handleFromStep(
            WebDriver driver,
            List<XPathData> steps,
            int currentIndex,
            List<WebElement> found
    ) {

        if (found.isEmpty()) {
            return List.of();
        }

        int nextIndex = currentIndex + 1;

        // NEW: Only take the FIRST element and discard all others
        WebElement element = found.get(0);
        boolean handledSpecial = false;

        // 1. IFRAME / FRAME case
        String tagName = element.getTagName(); // stale here will propagate
        System.out.println("Matched: " + tagName );
        if ("iframe".equalsIgnoreCase(tagName) || "frame".equalsIgnoreCase(tagName)) {
            handledSpecial = true;
            try {
                // stale during switch will propagate out of resolveXPathChain
                System.out.println("Switching to iframe/frame");
                driver.switchTo().frame(element);

                // NEW: No switch back to defaultContent() here.
                // We stay in this frame context for all subsequent steps.
                return resolveStep(driver, steps, nextIndex, List.of(driver));

            } catch (NoSuchFrameException ignored) {
                ignored.printStackTrace();
                // Frame not available – fall through and treat as non-special
                handledSpecial = false;
            }
        }

        // 2. Shadow root case (Selenium 4+)
        if (!handledSpecial) {
            SearchContext shadowRoot = getShadowRootIfAvailable(element);
            if (shadowRoot != null) {
                handledSpecial = true;
                // Any stale from inside this resolveStep will propagate
                return resolveStep(driver, steps, nextIndex, List.of(shadowRoot));
            }
        }

        // 3. If neither iframe/frame nor shadow root, treat element as normal context
        return resolveStep(driver, steps, nextIndex, List.of(element));
    }

    /**
     * Get shadow root if supported; suppress only "feature not supported" kind of errors.
     * StaleElementReferenceException and other WebDriverExceptions are NOT swallowed.
     */
    private static SearchContext getShadowRootIfAvailable(WebElement element) {
        try {
            return element.getShadowRoot();
        } catch (UnsupportedOperationException | NoSuchShadowRootException e) {
            // Driver does not support shadow DOM; treat as "no shadow root"
            return null;
        }
        // Any StaleElementReferenceException or other WebDriverException will propagate
    }
}
