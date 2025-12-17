package tools.dscode.common.domoperations;

import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.JavascriptException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.TimeoutException;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static tools.dscode.common.util.DebugUtils.printDebug;

public final class LeanWaits {

    private LeanWaits() {
    }

    public static void waitForPhraseEntities( PhraseData parsingPhrase) {

        // SAFE version: never throws
        safeWaitForPageReady(parsingPhrase.webDriver, Duration.ofSeconds(60));

        List<ElementMatch> elementMatches = parsingPhrase.getNextComponents(-1, "elementMatch")
                .stream().map(c -> (ElementMatch) c).toList();

        for (ElementMatch elementMatch : elementMatches) {
            try {
                elementMatch.findWebElements();
            } catch (Throwable t) {
                if(!elementMatch.selectionType.equals("any"))
                {
                    throw new RuntimeException("Failed to find WebElements for " + elementMatch, t);
                }
                continue;   // continue to next elementMatch
            }



            for (ElementWrapper elementWrapper : elementMatch.wrappedElements) {
                safeWaitForElementReady(parsingPhrase.webDriver, elementWrapper.element, Duration.ofSeconds(60));
            }
        }
    }

    public static void safeWaitForElementReady(
            WebDriver driver,
            WebElement element,
            Duration timeout
    ) {
        try {
            waitForElementReady(driver, element, timeout);
        } catch (Exception e) {
            System.out.println("[WARN] Element did NOT become ready: " + element);
            System.out.println("Cause: " + e);
            e.printStackTrace(System.out);
        }
    }

    public static void safeWaitForPageReady(WebDriver driver, Duration timeout) {
        try {
            waitForPageReady(driver, timeout);
        } catch (Exception e) {
            System.out.println("[WARN] Page did NOT reach ready state in time: " + e);
            e.printStackTrace(System.out);
        }
    }

    /**
     * Concise page readiness:
     * 1) document.readyState == 'complete'
     * 2) short "quiet period" (no DOM mutations) to let last micro-updates settle
     *
     * The total time for both phases will not exceed the given timeout.
     */
    public static void waitForPageReady(WebDriver driver, Duration timeout) {
        final Instant start = Instant.now();
        final long timeoutMillis = timeout.toMillis();

        // ---- Phase 1: document.readyState === "complete" ----
        FluentWait<WebDriver> readyWait = new FluentWait<>(driver)
                .withTimeout(timeout)
                .pollingEvery(Duration.ofMillis(500))
                .ignoring(JavascriptException.class)
                .ignoring(WebDriverException.class);

        readyWait.until(d -> {
            Object stateObj = ((JavascriptExecutor) d).executeScript("return document.readyState");
            String state = stateObj == null ? "null" : stateObj.toString();
            return "complete".equals(state);
        });

        // Calculate remaining time for quiet period
        long elapsedMillis = Duration.between(start, Instant.now()).toMillis();
        long remainingMillis = timeoutMillis - elapsedMillis;
        if (remainingMillis <= 0) {
            // Out of budget; we at least got readyState == complete.
            return;
        }

        Duration quietTimeout = Duration.ofMillis(Math.min(remainingMillis, 5000)); // cap quiet period to 5s max

        // ---- Phase 2: short DOM quiet period (MutationObserver-based) ----
        FluentWait<WebDriver> quietWait = new FluentWait<>(driver)
                .withTimeout(quietTimeout)
                .pollingEvery(Duration.ofMillis(800))
                .ignoring(JavascriptException.class)
                .ignoring(WebDriverException.class);

        quietWait.until((Function<WebDriver, Boolean>) d -> {
            Object result = ((JavascriptExecutor) d).executeAsyncScript(QUIET_PERIOD_JS, 300);
            if (result instanceof Boolean b) {
                return b;
            }
            return Boolean.TRUE.equals(result);
        });
    }

    /**
     * Concise, general element readiness (any tag/custom/shadow):
     * visible, centered into view, and hit-testable (not covered).
     * Returns the same element on success; throws TimeoutException on failure.
     *
     * NOTE: If the passed WebElement goes stale, re-locate it before calling this.
     */
    public static WebElement waitForElementReady(WebDriver driver,
                                                        WebElement element,
                                                        Duration timeout) {
        FluentWait<WebDriver> wait = new FluentWait<>(driver)
                .withTimeout(timeout)
                .pollingEvery(Duration.ofMillis(150))
                .ignoring(ElementClickInterceptedException.class)
                .ignoring(JavascriptException.class)
                .ignoring(WebDriverException.class);

        String elementString  = element.getText();
        printDebug("##waitForElementReady: " + (elementString.length() > 200 ? elementString.substring(0, 200) : elementString));
        System.out.println("Waiting for element to become ready " + timeout + "ms: " + element.toString());
        return wait.until(d -> {
            try {
                if (element == null) {
                    return null;
                }
                boolean displayed;
                try {
                    displayed = element.isDisplayed();
                } catch (StaleElementReferenceException stale) {
                    // let caller re-locate if needed; continue polling until timeout
                    return null;
                }
                if (!displayed) {
                    return null;
                }
                // Center into viewport to avoid sticky headers/partial visibility
                try {
                    ((JavascriptExecutor) d).executeScript(
                            "try{arguments[0].scrollIntoView({block:'center',inline:'center'});}catch(e){}",
                            element
                    );
                } catch (JavascriptException ignored) {

                    // ignore and continue polling
                }
                // Small hover nudge for CSS :hover menus/tooltips
                try {
                    new Actions(d).moveToElement(element)
                            .pause(Duration.ofMillis(100))
                            .perform();
                } catch (WebDriverException ignored) {
                    // hover failures shouldn't abort; just keep polling
                }
                // JS hit-test at center (shadow DOM aware) + style checks
                Boolean ok;
                try {
                    ok = (Boolean) ((JavascriptExecutor) d).executeScript(HIT_TEST_JS, element);
                } catch (JavascriptException ignored) {
                    return null;
                }
                return Boolean.TRUE.equals(ok) ? element : null;

            } catch (StaleElementReferenceException stale) {
                // The reference is dead; the caller should re-locate and call again.
                return null;
            }
        });
    }

    // --- minimal JS helpers (kept tiny) ---

    /**
     * short "quiet period": resolve true if no mutations for given ms.
     * Uses setTimeout instead of requestAnimationFrame to avoid background-tab throttling issues.
     */
    private static final String QUIET_PERIOD_JS =
            """
            const quietMs = arguments[0];
            const done = arguments[arguments.length - 1];
            const start = Date.now();
            let last = start;
            
            const mo = new MutationObserver(() => { last = Date.now(); });
            mo.observe(document, {subtree:true, childList:true, attributes:true, characterData:true});
            
            function check() {
              const now = Date.now();
              if ((now - last) >= quietMs) {
                mo.disconnect();
                done(true);
                return;
              }
              if ((now - start) > (quietMs * 6)) {
                // Bounded: if the page is very noisy, we still resolve after ~6 * quietMs
                mo.disconnect();
                done(true);
                return;
              }
              const delay = Math.min(quietMs, 250);
              setTimeout(check, delay);
            }
            
            check();
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
