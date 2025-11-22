package tools.dscode.common.domoperations;

import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.JavascriptException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.FluentWait;
import tools.dscode.common.treeparsing.PhraseExecution;
import tools.dscode.common.treeparsing.PhraseExecution.ElementMatch;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import static tools.dscode.common.util.DebugUtils.printDebug;

public final class LeanWaits {

    private LeanWaits() {}





    public static void waitForPhraseEntities(ChromiumDriver driver, PhraseExecution parsingPhrase) {
        driver.switchTo().defaultContent();
        waitForPageReady(driver, Duration.ofSeconds(60));
        List<ElementMatch> elementMatches = parsingPhrase.getNextComponents(-1, "elementMatch").stream()
                .map(c -> (ElementMatch)c)
                .toList();

        printDebug("@@##elementMatches.size: " + elementMatches.size());
        printDebug("@@##elementMatches: " + elementMatches);
        for(ElementMatch elementMatch : elementMatches){
           elementMatch.findWebElements(driver);
            System.out.println("@@elementMatch.matchedElements..getWrappers().size() "+ elementMatch.matchedElements.getWrappers().size());
           if(!elementMatch.selectionType.equals("any") && elementMatch.matchedElements.isEmpty())
               throw new RuntimeException("No elements found for " + elementMatch);

            for(WrappedWebElement element : elementMatch.matchedElements.getWrappers()){
                waitForElementReady(driver, element, Duration.ofSeconds(60));
            }
        }
    }



    /**
     * Concise page readiness:
     *  1) document.readyState == 'complete'
     *  2) short "quiet period" (no DOM mutations) to let last micro-updates settle
     */
    public static void waitForPageReady(ChromiumDriver driver, Duration timeout) {
        var wait = new FluentWait<>(driver)
                .withTimeout(timeout)
                .pollingEvery(Duration.ofMillis(1000))
                .ignoring(JavascriptException.class)
                .ignoring(WebDriverException.class);

        // 1) readyState == complete
        wait.until(d -> "complete".equals(((JavascriptExecutor) d).executeScript("return document.readyState")));

        // 2) short DOM-quiet period (MutationObserver) – lightweight and bounded
        wait.until((Function<ChromiumDriver, Boolean>) d ->
                Boolean.TRUE.equals(((JavascriptExecutor) d).executeAsyncScript(QUIET_PERIOD_JS, 300))
        );
    }

    /**
     * Concise, general element readiness (any tag/custom/shadow):
     *  visible, enabled, centered into view, and hit-testable (not covered).
     *  Returns the same element on success; throws TimeoutException on failure.
     *
     *  NOTE: If the passed WebElement goes stale, re-locate it before calling this.
     */
    public static WrappedWebElement waitForElementReady(ChromiumDriver driver, WrappedWebElement element, Duration timeout) {
        printDebug("@@##waitForElementReady ");
//        printDebug("@@##element.getText(): " + element.getText() + "");
        var wait = new FluentWait<>(driver)
                .withTimeout(timeout)
                .pollingEvery(Duration.ofMillis(150))
                .ignoring(ElementClickInterceptedException.class)
                .ignoring(JavascriptException.class)
                .ignoring(WebDriverException.class);
        printDebug("@@##wait: " + wait);
        return wait.until(d -> {
            try {
                // Basic Selenium checks first
                printDebug("@@##element before ");
                printDebug("@@##element: " + element);
                printDebug("@@##element.getTagName: " + element.getTagName());
                printDebug("@@##element.getText: " + element.getText());
                printDebug("@@##element.isDisplayed(): " + element.isDisplayed());
                printDebug("@@##element.isEnabled(): " + element.isEnabled());
                printDebug("@@##element after ");
                if (element == null) return null;
                if (!element.isDisplayed() || !element.isEnabled()) return null;
                printDebug("@@##element isDisplayed and isEnabled");
                // Center into viewport to avoid sticky headers/partial visibility
                ((JavascriptExecutor) d).executeScript(
                        "try{arguments[0].scrollIntoView({block:'center',inline:'center'});}catch(e){}", element);
                printDebug("@@##element executeScript");
                // Small hover nudge for CSS :hover menus/tooltips
                new Actions(d).moveToElement(element).pause(Duration.ofMillis(100)).perform();
                printDebug("@@##Actions Actions");
                // JS hit-test at center (shadow DOM aware) + style checks
                Boolean ok = (Boolean) ((JavascriptExecutor) d).executeScript(HIT_TEST_JS, element);
                printDebug("@@##ok ok");
                return Boolean.TRUE.equals(ok) ? element : null;

            } catch (StaleElementReferenceException stale) {
                // The reference is dead; the caller should re-locate and call again.
                return null;
            }
        });
    }

    // --- minimal JS helpers (kept tiny) ---

    // short "quiet period": resolve true if no mutations for given ms
    private static final String QUIET_PERIOD_JS =
            """
            const quietMs = arguments[0];
            const done = arguments[arguments.length - 1];
            const start = performance.now();
            let last = start;
            const mo = new MutationObserver(() => { last = performance.now(); });
            mo.observe(document, {subtree:true, childList:true, attributes:true, characterData:true});
            function tick(){
              const now = performance.now();
              if ((now - last) >= quietMs) { mo.disconnect(); done(true); return; }
              if ((now - start) > (quietMs * 6)) { mo.disconnect(); done(true); return; } // bounded
              requestAnimationFrame(tick);
            }
            tick();
            """;

    // center-point hit test + basic style checks; shadow DOM aware via composedPath
    private static final String HIT_TEST_JS =
            """
            const el = arguments[0];
            if (!el) return false;
            const cs = getComputedStyle(el);
            if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity||'1') === 0) return false;
            if (cs.pointerEvents === 'none') return false;
            const r = el.getBoundingClientRect();
            if (r.width < 2 || r.height < 2) return false;
            const vw = (visualViewport ? visualViewport.width : innerWidth);
            const vh = (visualViewport ? visualViewport.height : innerHeight);
            // require some overlap with viewport
            const ox = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0));
            const oy = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
            if (ox * oy < 0.2 * r.width * r.height) return false;

            const clamp = (v, min, max) => Math.max(min, Math.min(max, v));
            const cx = clamp(r.left + r.width/2, 0, vw - 1);
            const cy = clamp(r.top  + r.height/2, 0, vh - 1);
            const topEl = document.elementFromPoint(cx, cy);
            if (!topEl) return false;

            if (topEl === el || el.contains(topEl)) return true;
            const root = topEl.getRootNode && topEl.getRootNode();
            const path = root && root.composedPath ? root.composedPath() : [];
            return Array.isArray(path) && path.includes(el);
            """;
}
