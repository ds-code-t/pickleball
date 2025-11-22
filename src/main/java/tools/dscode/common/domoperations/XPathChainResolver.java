package tools.dscode.common.domoperations;

import org.openqa.selenium.*;
import tools.dscode.common.treeparsing.PhraseExecution;

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
        printDebug("@@##resolveStep: " + index + " / " + steps.size() + " / " + currentContexts.size());
        XPathData current = steps.get(index);
        printDebug("@@##current: " + current);
        // 1. Find elements for current XPathData in all contexts
        List<WebElement> found = new ArrayList<>();
        for (SearchContext context : currentContexts) {
            found.addAll(context.findElements(current.xPathy().getLocator()));
        }

        // Deduplicate now
        found = found.stream().distinct().toList();

        printDebug("@@##found: " + found.size());
        if(!found.isEmpty()) printDebug("@@##found: " + found.getFirst().getTagName());

        // If this is the last step, we're done: return what we found
        if (index == steps.size() - 1) {
            return found;
        }

        // Otherwise, we need to prepare contexts and/or recurse into frames/shadow for next steps
        if (found.isEmpty()) {
            return List.of(); // nothing to continue with
        }

        if (current.isFrom()) {
            return handleFromStep(driver, steps, index, found);
        } else {
            // Simple case: next step runs relative to the elements found in this step
            return resolveStep(driver, steps, index + 1, found);
        }
    }

    /**
     * Special handling when current XPathData.isFrom == true.
     * - If elements are iframes/frames: run the remaining steps inside each frame.
     * - If elements have a shadow root: run the remaining steps inside the shadow root.
     * - Otherwise: run the remaining steps with the element itself as context.
     */
    private static List<WebElement> handleFromStep(
            WebDriver driver,
            List<XPathData> steps,
            int currentIndex,
            List<WebElement> found
    ) {
        System.out.println("@@steps.size: " + steps.size());
        System.out.println("@@steps: " + steps);
        List<WebElement> finalResults = new ArrayList<>();
        List<SearchContext> normalNextContexts = new ArrayList<>();

        int nextIndex = currentIndex + 1;

        for (WebElement element : found) {
            boolean handledSpecial = false;

            // 1. IFRAME / FRAME case
            String tagName = element.getTagName(); // stale here will propagate
            printDebug("@@##tagName: " + tagName);
            if ("iframe".equalsIgnoreCase(tagName) || "frame".equalsIgnoreCase(tagName)) {
                handledSpecial = true;
                printDebug("@@##handling iframe/frame: " + element);
                try {
                    // stale during switch will propagate out of resolveXPathChain
                    driver.switchTo().frame(element);
                    try {
                        printDebug("@@##iframeContext");
                        finalResults.addAll(
                                resolveStep(driver, steps, nextIndex, List.of(driver))
                        );
                    } finally {
                        printDebug("@@##defaultContent Context");
//                        driver.switchTo().defaultContent();
                    }
                } catch (NoSuchFrameException ignored) {
                    // Frame not available – skip this element as a frame context
                    // (Other exceptions, including stale, are not suppressed.)
                }
            }
            else {

                // 2. Shadow root case (Selenium 4+)
                SearchContext shadowRoot = getShadowRootIfAvailable(element);
                if (shadowRoot != null) {
                    handledSpecial = true;
                    // Any stale from inside this resolveStep will propagate
                    finalResults.addAll(
                            resolveStep(driver, steps, nextIndex, List.of(shadowRoot))
                    );
                }
            }

            // 3. If neither iframe/frame nor shadow root, treat element as normal context
            if (!handledSpecial) {
                normalNextContexts.add(element);
            }
        }

        // If there are any "plain" elements, continue chain relative to them too
        if (!normalNextContexts.isEmpty()) {
            finalResults.addAll(
                    resolveStep(driver, steps, nextIndex, normalNextContexts)
            );
        }

        return finalResults;
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
