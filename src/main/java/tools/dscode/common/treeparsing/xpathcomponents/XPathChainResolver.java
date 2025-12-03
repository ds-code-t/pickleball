package tools.dscode.common.treeparsing.xpathcomponents;

import com.xpathy.XPathy;
import org.openqa.selenium.NoSuchFrameException;
import org.openqa.selenium.NoSuchShadowRootException;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.ArrayList;
import java.util.List;

public class XPathChainResolver {

    public static List<WebElement> resolveXPathChain(
            WebDriver driver,
            List<XPathy> xPathyList
    ) {
        if (xPathyList == null || xPathyList.isEmpty()) {
            return List.of();
        }
        // Start from driver as the only context
        return resolveStep(driver, xPathyList, 0, List.of(driver));
    }

    private static List<WebElement> resolveStep(
            WebDriver driver,
            List<XPathy> steps,
            int index,
            List<? extends SearchContext> currentContexts
    ) {

        XPathy current = steps.get(index);

        // 1. Collect elements for this step from all current contexts
        List<WebElement> found = new ArrayList<>();
        for (SearchContext context : currentContexts) {
            found.addAll(context.findElements(current.getLocator()));
        }
        // Deduplicate
        found = found.stream().distinct().toList();

        boolean isLast = (index == steps.size() - 1);
        if (isLast || found.isEmpty()) {
            // Last step: just return whatever we found (flat list),
            // or nothing to continue with if empty.
            return found;
        }

        int nextIndex = index + 1;
        WebElement first = found.getFirst();

        // 2. For intermediate steps, inspect only the first element for iframe/shadow
        try {
            String tagName = first.getTagName();

            // IFRAME / FRAME: switch once and continue with driver as the only context
            if ("iframe".equalsIgnoreCase(tagName) || "frame".equalsIgnoreCase(tagName)) {
                driver.switchTo().frame(first);
                return resolveStep(driver, steps, nextIndex, List.of(driver));
            }

            // SHADOW HOST: use its shadow root as the only context
            SearchContext shadowRoot = getShadowRootIfAvailable(first);
            if (shadowRoot != null) {
                return resolveStep(driver, steps, nextIndex, List.of(shadowRoot));
            }

        } catch (NoSuchFrameException e) {
            // If frame switching fails, fall through and treat like normal elements
        }

        // 3. Neither iframe/frame nor shadow host: use *all* found elements as contexts
        return resolveStep(driver, steps, nextIndex, found);
    }

    /**
     * Get shadow root if available; only swallows "feature not supported" kinds of errors.
     * StaleElementReferenceException or other WebDriverExceptions will propagate.
     */
    private static SearchContext getShadowRootIfAvailable(WebElement element) {
        try {
            return element.getShadowRoot();
        } catch (UnsupportedOperationException | NoSuchShadowRootException e) {
            return null;
        }
    }
}
