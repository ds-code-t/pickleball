package tools.dscode.common.domoperations;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.Select;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class HumanInteractions {

    private static final Duration SHORT = Duration.ofMillis(70);
    private static final Duration MID = Duration.ofMillis(120);
    private static final String CENTER_SCROLL_SCRIPT =
            "arguments[0].scrollIntoView({block:'center',inline:'center'});";

    private HumanInteractions() {
    }

    public static void blur(WebDriver driver) {
        ((JavascriptExecutor) driver).executeScript(
                "if (document.activeElement) { document.activeElement.blur(); }"
        );
    }

    // =======================
    // Mouse interactions
    // =======================

    public static void selectDropdownByIndex(WebDriver driver,
                                             WebElement element,
                                             int index) {
        Objects.requireNonNull(element, "element must not be null");
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }

        centerScroll(driver, element);

        Select select = new Select(element);
        int optionCount = select.getOptions().size();

        if (index >= optionCount) {
            throw new IllegalArgumentException(
                    "index " + index + " out of bounds (options=" + optionCount + ")"
            );
        }
        select.selectByIndex(index);
    }

    public static void selectDropdownByVisibleText(WebDriver driver,
                                                   WebElement container,
                                                   ValueWrapper valueWrapper) {
        Objects.requireNonNull(container, "container must not be null");
        Objects.requireNonNull(valueWrapper, "valueWrapper must not be null");

        String text = valueWrapper.asNormalizedText();
        boolean caseSensitive = valueWrapper.type.equals(ValueWrapper.ValueTypes.DOUBLE_QUOTED);
        String needle = caseSensitive ? text : text.toLowerCase(Locale.ROOT);
        List<WebElement> matches = container.findElements(By.xpath(".//option | .//a"));

        for (WebElement el : matches) {
            String hay = el.getText()
                    .trim()
                    .replaceAll("\\s+", " ");
            hay = caseSensitive ? hay : hay.toLowerCase(Locale.ROOT);

            if (!hay.equals(needle)) continue;

            if ("option".equalsIgnoreCase(el.getTagName())) {
                WebElement selectEl = el.findElement(By.xpath("ancestor::select[1]"));
                centerScroll(driver, selectEl);

                Select sel = new Select(selectEl);
                List<WebElement> opts = sel.getOptions();
                for (int i = 0; i < opts.size(); i++) {
                    if (opts.get(i).equals(el)) {
                        sel.selectByIndex(i);
                        return;
                    }
                }
                sel.selectByVisibleText(el.getText());
                return;
            }
            centerScroll(driver, el);
            el.click();
            return;
        }

        throw new NoSuchElementException("No matching <option> or <a> for text: " + text);
    }

    /**
     * Move, hover briefly, and click. JS-dispatch fallback if Actions fails.
     *
     * <p>The public signature remains WebElement-compatible. When the supplied element is an
     * ElementWrapper, direct WebElement calls dispatch to the wrapper and JavaScript fallbacks
     * use ElementWrapper.executeScript so stale-element relocation remains available.</p>
     */
    public static void click(WebDriver driver, WebElement el) {
        Objects.requireNonNull(el, "element must not be null");
        try {
            centerScroll(driver, el);
            new Actions(driver)
                    .moveToElement(el)
                    .pause(SHORT)
                    .click(el)
                    .pause(SHORT)
                    .build().perform();
        } catch (RuntimeException e) {
            try {
                centerScroll(driver, el);
                el.click();
            } catch (RuntimeException e2) {
                jsClick(driver, el);
            }
        }
    }

    public static void doubleClick(WebDriver driver, WebElement el) {
        Objects.requireNonNull(el, "element must not be null");
        try {
            centerScroll(driver, el);
            new Actions(driver)
                    .moveToElement(el).pause(SHORT)
                    .doubleClick(el)
                    .pause(SHORT)
                    .build().perform();
        } catch (RuntimeException e) {
            try {
                checkElementBeforeActions(el);
                new Actions(driver).doubleClick(el).perform();
            } catch (RuntimeException e2) {
                jsDispatchMouse(driver, el, "dblclick");
            }
        }
    }

    public static void contextClick(WebDriver driver, WebElement el) {
        Objects.requireNonNull(el, "element must not be null");
        try {
            centerScroll(driver, el);
            new Actions(driver)
                    .moveToElement(el).pause(MID)
                    .contextClick(el)
                    .pause(SHORT)
                    .build().perform();
        } catch (RuntimeException e) {
            try {
                checkElementBeforeActions(el);
                new Actions(driver).contextClick(el).perform();
            } catch (RuntimeException e2) {
                jsDispatchMouse(driver, el, "contextmenu");
            }
        }
    }

    public static void hover(WebDriver driver, WebElement el) {
        Objects.requireNonNull(el, "element must not be null");
        try {
            centerScroll(driver, el);
            new Actions(driver)
                    .moveToElement(el).pause(Duration.ofMillis(250))
                    .build().perform();
        } catch (RuntimeException e) {
            jsHover(driver, el);
        }
    }

    public static void dragAndDrop(WebDriver driver, WebElement source, WebElement target) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        try {
            centerScroll(driver, source);
            centerScroll(driver, target);
            new Actions(driver)
                    .moveToElement(source).pause(SHORT)
                    .clickAndHold(source)
                    .pause(MID)
                    .moveToElement(target).pause(MID)
                    .release(target)
                    .pause(SHORT)
                    .build().perform();
        } catch (RuntimeException e) {
            jsHtml5DragDrop(driver, source, target);
        }
    }

    public static void dragByOffset(WebDriver driver, WebElement source, int xOffset, int yOffset) {
        Objects.requireNonNull(source, "source must not be null");
        try {
            centerScroll(driver, source);
            new Actions(driver)
                    .moveToElement(source).pause(SHORT)
                    .clickAndHold(source)
                    .pause(MID)
                    .moveByOffset(xOffset, yOffset).pause(MID)
                    .release()
                    .pause(SHORT)
                    .build().perform();
        } catch (RuntimeException e) {
            jsHtml5DragBy(driver, source, xOffset, yOffset);
        }
    }

    public static void wheelScrollBy(WebDriver driver, WebElement el) {
        wheelScrollBy(driver, el, 0);
    }

    public static void wheelScrollBy(WebDriver driver, WebElement el, int deltaY) {
        Objects.requireNonNull(el, "element must not be null");
        try {
            centerScroll(driver, el);
            new Actions(driver)
                    .scrollByAmount(0, deltaY).pause(SHORT)
                    .build().perform();
        } catch (RuntimeException e) {
            jsScrollBy(driver, el, deltaY);
        }
    }

    // =======================
    // Keyboard interactions
    // =======================

    public static void clearAndType(WebDriver driver, WebElement el, CharSequence text) {
        Objects.requireNonNull(el, "element must not be null");
        final String s = text == null ? "" : text.toString();
        try {
            focus(driver, el);
            el.sendKeys(Keys.chord(osControlKey(), "a"));
            el.sendKeys(Keys.DELETE);
            if (!s.isEmpty()) el.sendKeys(s);
        } catch (RuntimeException e) {
            jsSetValue(driver, el, s, true);
        }
    }

    public static void clear(WebDriver driver, WebElement el) {
        Objects.requireNonNull(el, "element must not be null");
        focus(driver, el);
        el.sendKeys(Keys.chord(osControlKey(), "a"));
        el.sendKeys(Keys.DELETE);
    }

    public static void typeText(WebDriver driver, WebElement el, CharSequence text) {
        if (el == null) {
            typeText(driver, text);
            return;
        }
        final String s = text == null ? "" : text.toString();
        try {
            focus(driver, el);
            if (!s.isEmpty()) el.sendKeys(s);
        } catch (RuntimeException e) {
            jsAppendValue(driver, el, s);
        }
    }

    public static void typeText(WebDriver driver, CharSequence text) {
        final String s = text == null ? "" : text.toString();
        try {
            if (!s.isEmpty()) {
                new Actions(driver).sendKeys(s).perform();
            }
        } catch (RuntimeException e) {
            jsAppendValueToActive(driver, s);
        }
    }

    private static void jsAppendValueToActive(WebDriver driver, String text) {
        if (text == null || text.isEmpty()) return;
        ((JavascriptExecutor) driver).executeScript(
                """
                        const el = document.activeElement;
                        if (el && 'value' in el) {
                            el.value += arguments[0];
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                            el.dispatchEvent(new Event('change', { bubbles: true }));
                        }
                        """,
                text
        );
    }

    public static void sendKeys(WebDriver driver, CharSequence... keys) {
        new Actions(driver)
                .pause(SHORT)
                .sendKeys(keys).pause(SHORT)
                .build().perform();
    }

    public static void pressEnter(WebDriver driver, WebElement el) {
        Objects.requireNonNull(el, "element must not be null");
        try {
            focus(driver, el);
            el.sendKeys(Keys.ENTER);
        } catch (RuntimeException e) {
            jsDispatchKeyboard(driver, el, "Enter");
        }
    }

    public static void pressEsc(WebDriver driver) {
        sendKeys(driver, Keys.ESCAPE);
    }

    // =======================
    // Helpers
    // =======================

    private static Keys osControlKey() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") ? Keys.COMMAND : Keys.CONTROL;
    }

    /**
     * Center-scroll to reduce sticky header/overlay issues. ElementWrapper owns stale retry;
     * ordinary WebElements use the same script through the driver. A second stale failure is
     * propagated, while other WebDriver scrolling failures remain best-effort.
     */
    public static void centerScroll(WebDriver driver, WebElement el) {
        Objects.requireNonNull(el, "element must not be null");
        try {
            executeElementScript(driver, CENTER_SCROLL_SCRIPT, el);
        } catch (StaleElementReferenceException stale) {
            throw stale;
        } catch (WebDriverException ignored) {
            // Preserve best-effort scrolling for non-stale WebDriver failures.
        }
    }

    private static void focus(WebDriver driver, WebElement el) {
        centerScroll(driver, el);
        try {
            new Actions(driver)
                    .moveToElement(el).pause(SHORT)
                    .click(el).pause(SHORT)
                    .build().perform();
        } catch (RuntimeException ignore) {
            // Verify and force focus below.
        }
        if (!isActiveElement(driver, el)) {
            jsFocus(driver, el);
        }
    }

    private static boolean isActiveElement(WebDriver driver, WebElement el) {
        try {
            Object result = executeElementScript(
                    driver,
                    "return document.activeElement === arguments[0];",
                    el
            );
            return Boolean.TRUE.equals(result);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Performs a low-cost element command before an Actions chain when no preceding scroll has
     * already exercised the element. For ElementWrapper this invokes its stale retry; for a raw
     * WebElement it preserves normal Selenium behavior.
     */
    private static void checkElementBeforeActions(WebElement element) {
        element.isEnabled();
    }

    /**
     * Executes a script with element supplied as arguments[0]. ElementWrapper owns stale retry;
     * ordinary WebElements are passed directly to Selenium without manual unwrapping.
     */
    private static Object executeElementScript(WebDriver driver,
                                               String script,
                                               WebElement element,
                                               Object... additionalArguments) {
        Objects.requireNonNull(driver, "driver must not be null");
        Objects.requireNonNull(script, "script must not be null");
        Objects.requireNonNull(element, "element must not be null");

        Object[] extra = additionalArguments == null ? new Object[0] : additionalArguments;
        if (element instanceof ElementWrapper wrapper) {
            return wrapper.executeScript(script, extra);
        }

        Object[] arguments = new Object[extra.length + 1];
        arguments[0] = element;
        System.arraycopy(extra, 0, arguments, 1, extra.length);
        return ((JavascriptExecutor) driver).executeScript(script, arguments);
    }

    private static void jsFocus(WebDriver driver, WebElement el) {
        executeElementScript(driver, """
                const el = arguments[0];
                if(!el) return;
                try { el.focus && el.focus({preventScroll:true}); }
                catch(e) { try { el.focus && el.focus(); } catch(e2){} }
                """, el);
    }

    private static void jsClick(WebDriver driver, WebElement el) {
        executeElementScript(driver, """
                const el = arguments[0];
                if(!el) return;
                const fire = (type) => el.dispatchEvent(new MouseEvent(type, {bubbles:true, cancelable:true, view:window}));
                try { el.focus && el.focus({preventScroll:true}); } catch(e) { try{ el.focus && el.focus(); }catch(e2){} }
                fire('mouseover'); fire('mousedown'); fire('mouseup'); fire('click');
                """, el);
    }

    private static void jsDispatchMouse(WebDriver driver, WebElement el, String type) {
        executeElementScript(driver, """
                const el = arguments[0], type = arguments[1];
                if(!el) return;
                el.dispatchEvent(new MouseEvent(type, {bubbles:true, cancelable:true, view:window, button: (type==='contextmenu'?2:0)}));
                """, el, type);
    }

    private static void jsHover(WebDriver driver, WebElement el) {
        executeElementScript(driver, """
                const el = arguments[0];
                if(!el) return;
                el.dispatchEvent(new MouseEvent('mouseover', {bubbles:true, cancelable:true, view:window}));
                el.dispatchEvent(new MouseEvent('mouseenter', {bubbles:true, cancelable:true, view:window}));
                """, el);
    }

    private static void jsSetValue(WebDriver driver, WebElement el, String value, boolean clear) {
        executeElementScript(driver, """
                const el = arguments[0], val = arguments[1], clr = arguments[2];
                if(!el) return;
                if (clr) el.value = '';
                el.value = val;
                el.dispatchEvent(new Event('input',  {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                """, el, value, clear);
    }

    private static void jsAppendValue(WebDriver driver, WebElement el, String value) {
        executeElementScript(driver, """
                const el = arguments[0], val = arguments[1];
                if(!el) return;
                el.value = (el.value ?? '') + val;
                el.dispatchEvent(new Event('input',  {bubbles:true}));
                """, el, value);
    }

    private static void jsDispatchKeyboard(WebDriver driver, WebElement el, String key) {
        executeElementScript(driver, """
                const el = arguments[0], key = arguments[1];
                if(!el) return;
                try { el.focus && el.focus({preventScroll:true}); } catch(e) { try{ el.focus && el.focus(); }catch(e2){} }
                const opts = {bubbles:true, cancelable:true, key:key, code:key};
                el.dispatchEvent(new KeyboardEvent('keydown', opts));
                el.dispatchEvent(new KeyboardEvent('keypress', opts));
                el.dispatchEvent(new KeyboardEvent('keyup', opts));
                """, el, key);
    }

    private static void jsScrollBy(WebDriver driver, WebElement el, int dy) {
        executeElementScript(driver, """
                const el = arguments[0], dy = arguments[1];
                try{ el.scrollBy(0, dy); }catch(e){ window.scrollBy(0, dy); }
                """, el, dy);
    }

    /**
     * Uses source as the primary retry owner. If source is an ElementWrapper, the complete script
     * is retried once after source relocation. Target is passed directly and may itself be a
     * WrapsElement; no manual unwrapping is needed.
     */
    private static void jsHtml5DragDrop(WebDriver driver, WebElement source, WebElement target) {
        executeElementScript(driver, """
                const src = arguments[0], tgt = arguments[1];
                const dt = new DataTransfer();
                function fire(el, type, dt){
                  const e = new DragEvent(type, {bubbles:true, cancelable:true, dataTransfer:dt});
                  return el.dispatchEvent(e);
                }
                src.scrollIntoView({block:'center', inline:'center'});
                tgt.scrollIntoView({block:'center', inline:'center'});
                fire(src,'dragstart',dt);
                fire(tgt,'dragenter',dt);
                fire(tgt,'dragover',dt);
                fire(tgt,'drop',dt);
                fire(src,'dragend',dt);
                """, source, target);
    }

    private static void jsHtml5DragBy(WebDriver driver, WebElement source, int dx, int dy) {
        executeElementScript(driver, """
                const src = arguments[0], dx = arguments[1], dy = arguments[2];
                const dt = new DataTransfer();
                function fireAt(el, type, clientX, clientY, dt){
                  const e = new DragEvent(type, {bubbles:true, cancelable:true, clientX, clientY, dataTransfer:dt});
                  el.dispatchEvent(e);
                }
                const r = src.getBoundingClientRect();
                const startX = r.left + r.width/2;
                const startY = r.top  + r.height/2;
                fireAt(src,'dragstart',startX,startY,dt);
                fireAt(document,'dragover',startX+dx,startY+dy,dt);
                fireAt(document,'drop',startX+dx,startY+dy,dt);
                fireAt(src,'dragend',startX+dx,startY+dy,dt);
                """, source, dx, dy);
    }


    private static String normalizeText(String s) {
        if (s == null) return "";
        return s
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void jsSelectByVisibleText(WebDriver driver,
                                              WebElement selectEl,
                                              String visibleText) {
        executeElementScript(driver, """
                const sel = arguments[0];
                const targetText = arguments[1];
                if (!sel) return;

                const norm = s => String(s ?? '').replace(/\\s+/g, ' ').trim();
                const wanted = norm(targetText);
                let found = null;

                const options = sel.options || [];
                for (let i = 0; i < options.length; i++) {
                  const opt = options[i];
                  if (norm(opt.textContent) === wanted) {
                    sel.selectedIndex = i;
                    opt.selected = true;
                    found = opt;
                    break;
                  }
                }

                if (!found) return;
                sel.dispatchEvent(new Event('input',  {bubbles:true}));
                sel.dispatchEvent(new Event('change', {bubbles:true}));
                """, selectEl, visibleText);
    }
}
