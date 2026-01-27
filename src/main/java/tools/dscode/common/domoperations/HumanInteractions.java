package tools.dscode.common.domoperations;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.Select;
import tools.dscode.common.assertions.ValueWrapper;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class HumanInteractions {

    private static final Duration SHORT = Duration.ofMillis(70);
    private static final Duration MID = Duration.ofMillis(120);

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
     * IMPORTANT: Use element-targeted click(el) to avoid "active element" ambiguity.
     */
    public static void click(WebDriver driver, WebElement el) {
        Objects.requireNonNull(el);
        try {
            centerScroll(driver, el);
            new Actions(driver)
                    .moveToElement(el)
                    .pause(SHORT)
                    .click(el)            // <-- target element explicitly
                    .pause(SHORT)
                    .build().perform();
        } catch (RuntimeException e) {
            jsClick(driver, el);
        }
    }

    /**
     * Move and double-click (user-like). JS fallback dispatches dblclick.
     */
    public static void doubleClick(WebDriver driver, WebElement el) {
        Objects.requireNonNull(el);
        try {
            centerScroll(driver, el);
            new Actions(driver)
                    .moveToElement(el).pause(SHORT)
                    .doubleClick(el)      // <-- target element explicitly
                    .pause(SHORT)
                    .build().perform();
        } catch (RuntimeException e) {
            jsDispatchMouse(driver, el, "dblclick");
        }
    }

    /**
     * Move and context-click. JS fallback dispatches contextmenu.
     */
    public static void contextClick(WebDriver driver, WebElement el) {
        Objects.requireNonNull(el);
        try {
            centerScroll(driver, el);
            new Actions(driver)
                    .moveToElement(el).pause(MID)
                    .contextClick(el)     // <-- target element explicitly
                    .pause(SHORT)
                    .build().perform();
        } catch (RuntimeException e) {
            jsDispatchMouse(driver, el, "contextmenu");
        }
    }

    /**
     * Just hover; useful to open menus/tooltips. JS fallback dispatches mouseover/mouseenter.
     */
    public static void hover(WebDriver driver, WebElement el) {
        Objects.requireNonNull(el);
        try {
            centerScroll(driver, el);
            new Actions(driver)
                    .moveToElement(el).pause(Duration.ofMillis(250))
                    .build().perform();
        } catch (RuntimeException e) {
            jsHover(driver, el);
        }
    }

    /**
     * Drag source element onto target. JS fallback uses HTML5 drag/drop dispatch.
     */
    public static void dragAndDrop(WebDriver driver, WebElement source, WebElement target) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        try {
            centerScroll(driver, source);
            centerScroll(driver, target);
            new Actions(driver)
                    .moveToElement(source).pause(SHORT)
                    .clickAndHold(source)       // <-- target explicitly
                    .pause(MID)
                    .moveToElement(target).pause(MID)
                    .release(target)            // <-- target explicitly
                    .pause(SHORT)
                    .build().perform();
        } catch (RuntimeException e) {
            jsHtml5DragDrop(driver, source, target);
        }
    }

    /**
     * Drag by an x/y offset. JS fallback tries HTML5 drag with offset.
     */
    public static void dragByOffset(WebDriver driver, WebElement source, int xOffset, int yOffset) {
        Objects.requireNonNull(source);
        try {
            centerScroll(driver, source);
            new Actions(driver)
                    .moveToElement(source).pause(SHORT)
                    .clickAndHold(source)       // <-- target explicitly
                    .pause(MID)
                    .moveByOffset(xOffset, yOffset).pause(MID)
                    .release()                  // release at current location
                    .pause(SHORT)
                    .build().perform();
        } catch (RuntimeException e) {
            jsHtml5DragBy(driver, source, xOffset, yOffset);
        }
    }

    /**
     * Wheel-scroll the element into view center, then nudge by deltaY pixels.
     */
    public static void wheelScrollBy(WebDriver driver, WebElement el) {
        wheelScrollBy(driver, el, 0);
    }

    public static void wheelScrollBy(WebDriver driver, WebElement el, int deltaY) {
        Objects.requireNonNull(el);
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

    /**
     * Focus element, clear (Ctrl/Cmd+A + Delete), then type text.
     * JS fallback sets value + events.
     *
     * IMPORTANT: Use element-targeted sendKeys via WebElement to avoid "active element" bleed.
     */
    public static void clearAndType(WebDriver driver, WebElement el, CharSequence text) {
        Objects.requireNonNull(el);
        final String s = text == null ? "" : text.toString();
        try {
            focus(driver, el);

            // Prefer element-targeted operations.
            // Using chord avoids Actions' reliance on the active element.
            el.sendKeys(Keys.chord(osControlKey(), "a"));
            el.sendKeys(Keys.DELETE);
            if (!s.isEmpty()) el.sendKeys(s);

        } catch (RuntimeException e) {
            jsSetValue(driver, el, s, true);
        }
    }


    public static void clear(WebDriver driver, WebElement el) {
        Objects.requireNonNull(el);
            focus(driver, el);
            el.sendKeys(Keys.chord(osControlKey(), "a"));
            el.sendKeys(Keys.DELETE);
    }

    /**
     * Focus element and type text as-is.
     * JS fallback APPENDS (kept as-is); consider changing callers to use clearAndType if needed.
     *
     * IMPORTANT: Use element-targeted sendKeys via WebElement to avoid "active element" bleed.
     */
    public static void typeText(WebDriver driver, WebElement el, CharSequence text) {
        Objects.requireNonNull(el);
        final String s = text == null ? "" : text.toString();
        try {
            focus(driver, el);
            if (!s.isEmpty()) el.sendKeys(s);
        } catch (RuntimeException e) {
            jsAppendValue(driver, el, s);
        }
    }

    /**
     * Send raw keys to the currently focused element (e.g., ENTER, ESC).
     * NOTE: This intentionally targets the active element.
     */
    public static void sendKeys(WebDriver driver, CharSequence... keys) {
        try {
            new Actions(driver)
                    .pause(SHORT)
                    .sendKeys(keys).pause(SHORT)
                    .build().perform();
        } catch (RuntimeException e) {
            throw e;
        }
    }

    /**
     * Press ENTER on an element (focus first).
     *
     * IMPORTANT: Use element-targeted sendKeys via WebElement to avoid "active element" bleed.
     */
    public static void pressEnter(WebDriver driver, WebElement el) {
        Objects.requireNonNull(el);
        try {
            focus(driver, el);
            el.sendKeys(Keys.ENTER);
        } catch (RuntimeException e) {
            jsDispatchKeyboard(driver, el, "Enter");
        }
    }

    /**
     * Press ESC (global).
     */
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
     * Center-scroll to reduce sticky header/overlay issues.
     */
    private static void centerScroll(WebDriver driver, WebElement el) {
        ((JavascriptExecutor) driver).executeScript(
                "try{arguments[0].scrollIntoView({block:'center',inline:'center'});}catch(e){}", el);
    }

    /**
     * Ensure element is scrolled into view and is actually the active element.
     * This prevents Actions.sendKeys(...) style "bleed" across fields when focus is stolen.
     */
    private static void focus(WebDriver driver, WebElement el) {
        centerScroll(driver, el);

        // First attempt: element-targeted click via Actions (more "human" than JS focus alone)
        try {
            new Actions(driver)
                    .moveToElement(el).pause(SHORT)
                    .click(el).pause(SHORT)
                    .build().perform();
        } catch (RuntimeException ignore) {
            // Ignore here; we'll attempt JS focus next
        }

        // Verify / force focus via JS if needed
        if (!isActiveElement(driver, el)) {
            jsFocus(driver, el);
        }
    }

    private static boolean isActiveElement(WebDriver driver, WebElement el) {
        try {
            Object result = ((JavascriptExecutor) driver).executeScript(
                    "return document.activeElement === arguments[0];", el);
            return Boolean.TRUE.equals(result);
        } catch (RuntimeException e) {
            // If we can't verify, assume not focused so we can attempt jsFocus.
            return false;
        }
    }

    private static void jsFocus(WebDriver driver, WebElement el) {
        ((JavascriptExecutor) driver).executeScript(
                """
                        const el = arguments[0];
                        if(!el) return;
                        try { el.focus && el.focus({preventScroll:true}); }
                        catch(e) { try { el.focus && el.focus(); } catch(e2){} }
                        """,
                el
        );
    }

    private static void jsClick(WebDriver driver, WebElement el) {
        ((JavascriptExecutor) driver).executeScript(
                """
                        const el = arguments[0];
                        if(!el) return;
                        const fire = (type) => el.dispatchEvent(new MouseEvent(type, {bubbles:true, cancelable:true, view:window}));
                        try { el.focus && el.focus({preventScroll:true}); } catch(e) { try{ el.focus && el.focus(); }catch(e2){} }
                        fire('mouseover'); fire('mousedown'); fire('mouseup'); fire('click');
                        """, el);
    }

    private static void jsDispatchMouse(WebDriver driver, WebElement el, String type) {
        ((JavascriptExecutor) driver).executeScript(
                """
                        const el = arguments[0], type = arguments[1];
                        if(!el) return;
                        el.dispatchEvent(new MouseEvent(type, {bubbles:true, cancelable:true, view:window, button: (type==='contextmenu'?2:0)}));
                        """, el, type);
    }

    private static void jsHover(WebDriver driver, WebElement el) {
        ((JavascriptExecutor) driver).executeScript(
                """
                        const el = arguments[0];
                        if(!el) return;
                        el.dispatchEvent(new MouseEvent('mouseover', {bubbles:true, cancelable:true, view:window}));
                        el.dispatchEvent(new MouseEvent('mouseenter', {bubbles:true, cancelable:true, view:window}));
                        """, el);
    }

    private static void jsSetValue(WebDriver driver, WebElement el, String value, boolean clear) {
        ((JavascriptExecutor) driver).executeScript(
                """
                        const el = arguments[0], val = arguments[1], clr = arguments[2];
                        if(!el) return;
                        if (clr) el.value = '';
                        el.value = val;
                        el.dispatchEvent(new Event('input',  {bubbles:true}));
                        el.dispatchEvent(new Event('change', {bubbles:true}));
                        """, el, value, clear);
    }

    private static void jsAppendValue(WebDriver driver, WebElement el, String value) {
        ((JavascriptExecutor) driver).executeScript(
                """
                        const el = arguments[0], val = arguments[1];
                        if(!el) return;
                        el.value = (el.value ?? '') + val;
                        el.dispatchEvent(new Event('input',  {bubbles:true}));
                        """, el, value);
    }

    private static void jsDispatchKeyboard(WebDriver driver, WebElement el, String key) {
        ((JavascriptExecutor) driver).executeScript(
                """
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
        ((JavascriptExecutor) driver).executeScript(
                """
                        const el = arguments[0], dy = arguments[1];
                        try{ el.scrollBy(0, dy); }catch(e){ window.scrollBy(0, dy); }
                        """, el, dy);
    }

    /**
     * HTML5 drag/drop via DataTransfer; works for many modern UIs when Actions fails.
     */
    private static void jsHtml5DragDrop(WebDriver driver, WebElement source, WebElement target) {
        ((JavascriptExecutor) driver).executeScript(
                """
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
        ((JavascriptExecutor) driver).executeScript(
                """
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

    /**
     * Normalize text similar to other utility normalization: collapse whitespace, strip, handle NBSP.
     */
    private static String normalizeText(String s) {
        if (s == null) return "";
        return s
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * JS fallback for selecting an option by visible text in a native <select>.
     */
    private static void jsSelectByVisibleText(WebDriver driver,
                                              WebElement selectEl,
                                              String visibleText) {
        ((JavascriptExecutor) driver).executeScript(
                """
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

                        // Fire events as a user-like change
                        sel.dispatchEvent(new Event('input',  {bubbles:true}));
                        sel.dispatchEvent(new Event('change', {bubbles:true}));
                        """,
                selectEl, visibleText
        );
    }
}
