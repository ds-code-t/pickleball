package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.chrome.ChromeDriver;
import tools.dscode.common.composite.CompositeRuntime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Chrome (CDP, isolated world) against the {@code src/test/resources/overlay} fixtures.
 * Skips only when this machine has no Chrome binary.
 */
class OverlayRuntimeLiveTest {
    @TempDir
    Path project;

    private ChromeDriver browser;
    private OverlayFixtures fixtures;

    @BeforeEach
    void start() throws Exception {
        browser = OverlayFixtures.chrome();
        Assumptions.assumeTrue(browser != null, "skipped: no Chrome binary on this machine");
        fixtures = OverlayFixtures.start();
    }

    @AfterEach
    void release() {
        PageSurfaces.clearDriver();
        CompositeRuntime.resetForTests();
        if (browser != null) {
            try {
                browser.quit();
            } catch (RuntimeException ignored) {
                // The assertion already ran.
            }
        }
        if (fixtures != null) fixtures.close();
    }

    private void load(String page) {
        browser.get(fixtures.url(page));
        PageSurfaces.manage(browser, "chrome-headless-quiet", false);
        assertTrue(PageSurfaces.focus(browser), "page focus");
    }

    /** Remembers the element's visual-viewport box under its id, as a resolved query would. */
    private void remember(String id) {
        @SuppressWarnings("unchecked")
        Map<String, Object> box = (Map<String, Object>) browser.executeScript(
                "var r=document.getElementById(arguments[0]).getBoundingClientRect(); return {x:r.left,y:r.top,w:r.width,h:r.height};", id);
        PageSurfaces.rememberBounds(id, num(box, "x"), num(box, "y"), num(box, "w"), num(box, "h"), browser, browser.getWindowHandle());
    }

    private static double num(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).doubleValue();
    }

    private AgentControl.Result run(String op, Map<String, String> args) {
        return AgentControl.execute(project, "overlay-live", op, args, List.of(), "a");
    }

    /** Audit "input capture": clicks on a CDP-placed overlay landed in the isolated world and were never drained. */
    @Test
    void clickOnCdpPlacedOverlayIsDrained() throws Exception {
        load("basic.html");
        remember("save");
        Map<String, String> args = new LinkedHashMap<>();
        args.put("handle", "save");
        args.put("target", "page");
        args.put("text", "<button type=\"button\" class=\"done\">Done</button>");
        AgentControl.Result placed = run("overlay", args);
        assertEquals(0, placed.exit(), String.valueOf(placed.body()));
        String id = String.valueOf(placed.body().get("id"));
        browser.findElement(By.cssSelector("[data-pkb-id='" + id + "'] button.done")).click();
        AgentControl.Result polled = run("overlay-poll", Map.of("target", "page"));
        assertEquals(0, polled.exit(), String.valueOf(polled.body()));
        String events = String.valueOf(polled.body().get("events"));
        assertTrue(events.contains("click") && events.contains(id), "overlay click was not drained: " + events);
    }

    /** Audit item 6: the cached isolated-world context dies on a same-tab navigation. */
    @Test
    void placementAfterSameTabNavigationSucceeds() throws Exception {
        load("basic.html");
        remember("save");
        AgentControl.Result first = run("label", Map.of("handle", "save", "target", "page", "text", "first"));
        assertEquals(0, first.exit(), String.valueOf(first.body()));
        browser.get(fixtures.url("strict-csp.html"));
        remember("save");
        AgentControl.Result second = run("label", Map.of("handle", "save", "target", "page", "text", "second"));
        assertEquals(0, second.exit(), "placement after navigation failed: " + second.body());
        Object count = browser.executeScript("return document.querySelectorAll('[data-pkb-kind=label]').length");
        assertEquals(1L, ((Number) count).longValue());
    }

    private static final String RING = "var l=document.querySelector('[data-pkb-layer]'); var n=l&&l.shadowRoot.querySelector('.ring');";

    private String overlay(String handle, String text, String at) {
        remember(handle);
        Map<String, String> args = new LinkedHashMap<>();
        args.put("handle", handle);
        args.put("target", "page");
        args.put("text", text);
        if (at != null) args.put("at", at);
        AgentControl.Result placed = run("overlay", args);
        assertEquals(0, placed.exit(), String.valueOf(placed.body()));
        return String.valueOf(placed.body().get("id"));
    }

    /** Lets the runtime's requestAnimationFrame repositioning run. */
    private void frames(int count) {
        browser.manage().timeouts().scriptTimeout(Duration.ofSeconds(5));
        browser.executeAsyncScript("var done=arguments[arguments.length-1], n=arguments[0], i=0;"
                + "(function f(){ if(++i>n) done(); else requestAnimationFrame(f); })();", count);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rect(String find) {
        return (Map<String, Object>) browser.executeScript(find + " if(!n) return null; var r=n.getBoundingClientRect(), s=getComputedStyle(n);"
                + "return {l:r.left,t:r.top,r:r.right,b:r.bottom,w:r.width,h:r.height,vis:s.visibility,clip:s.clipPath,shadow:s.boxShadow};");
    }

    private Map<String, Object> target(String id) {
        return rect("var n=document.getElementById('" + id + "');");
    }

    private Map<String, Object> ring() {
        return rect(RING);
    }

    private Map<String, Object> placed(String id) {
        return rect("var n=document.querySelector('[data-pkb-id=\"" + id + "\"]');");
    }

    private static void assertSameBox(Map<String, Object> expected, Map<String, Object> actual) {
        assertNotNull(expected, "target missing");
        assertNotNull(actual, "ring missing");
        for (String key : List.of("l", "t", "w", "h")) {
            assertTrue(Math.abs(num(expected, key) - num(actual, key)) <= 1.01,
                    key + " differs: target " + expected + " ring " + actual);
        }
    }

    private static boolean apart(Map<String, Object> a, Map<String, Object> b) {
        return num(a, "r") <= num(b, "l") + 0.5 || num(b, "r") <= num(a, "l") + 0.5
                || num(a, "b") <= num(b, "t") + 0.5 || num(b, "b") <= num(a, "t") + 0.5;
    }

    /** Item 5 / D2: the ring is an outline only; the target's centre is still the target and a click reaches it. */
    @Test
    void ringDoesNotBlockTargetClick() {
        load("basic.html");
        overlay("save", "Click Save", null);
        frames(2);
        assertSameBox(target("save"), ring());
        Object hit = browser.executeScript("var r=document.getElementById('save').getBoundingClientRect();"
                + "return document.elementFromPoint(r.left+r.width/2, r.top+r.height/2).id;");
        assertEquals("save", hit);
        browser.findElement(By.id("save")).click();
        assertEquals(1L, ((Number) browser.executeScript("return window.saved||0")).longValue());
    }

    /** D2: the callout sits beside the target and never covers it. */
    @Test
    void calloutSitsBesideTarget() {
        load("basic.html");
        String id = overlay("save", "Click Save when ready", null);
        frames(2);
        Map<String, Object> callout = placed(id);
        assertTrue(apart(target("save"), callout), "callout covers the target: " + target("save") + " " + callout);
        assertEquals("visible", callout.get("vis"));
    }

    /** D2: --at=over keeps the old covering placement, but it is click-through. */
    @Test
    void atOverCoversTheTargetButPassesClicks() {
        load("basic.html");
        String id = overlay("save", "Over the button", "over");
        frames(2);
        Map<String, Object> callout = placed(id);
        assertTrue(Math.abs(num(callout, "l") - num(target("save"), "l")) <= 1.01, String.valueOf(callout));
        assertTrue(Math.abs(num(callout, "t") - num(target("save"), "t")) <= 1.01, String.valueOf(callout));
        browser.findElement(By.id("save")).click();
        assertEquals(1L, ((Number) browser.executeScript("return window.saved||0")).longValue());
        assertEquals(2, run("overlay", Map.of("handle", "save", "target", "page", "text", "x", "at", "middle")).exit());
    }

    /** Item 10: scrolling two nested containers moves the ring with the target. */
    @Test
    void nestedScrollMovesOverlayWithTarget() {
        load("nested-scroll.html");
        overlay("deep", "Deep target", null);
        browser.executeScript("document.getElementById('inner').scrollTop=150; document.getElementById('outer').scrollTop=100;");
        frames(2);
        Map<String, Object> ring = ring();
        assertSameBox(target("deep"), ring);
        assertEquals("visible", ring.get("vis"));
    }

    /** Item 10: a window resize moves a right-aligned target; the ring follows and the callout stays on screen. */
    @Test
    void windowResizeRepositions() {
        load("basic.html");
        String id = overlay("right-edge", "Resize me", null);
        frames(2);
        double before = num(target("right-edge"), "l");
        browser.manage().window().setSize(new Dimension(900, 800));
        frames(3);
        Map<String, Object> moved = target("right-edge");
        assertTrue(num(moved, "l") < before - 100, "fixture target did not move: " + before + " -> " + moved);
        assertSameBox(moved, ring());
        Map<String, Object> callout = placed(id);
        long width = ((Number) browser.executeScript("return document.documentElement.clientWidth")).longValue();
        assertTrue(num(callout, "r") <= width && num(callout, "l") >= 0, "callout left the viewport: " + callout);
        assertTrue(apart(moved, callout), "callout covers the target: " + callout);
    }

    /** Item 10: a CSS transform zoom on an ancestor is followed. */
    @Test
    void zoomViaTransformFollows() {
        load("nested-scroll.html");
        overlay("deep", "Zoomed", null);
        browser.executeScript("document.getElementById('inner').scrollTop=150; document.getElementById('outer').scrollTop=100;");
        frames(2);
        browser.executeScript("document.getElementById('wrap').style.transform='scale(1.25)';");
        frames(3);
        assertSameBox(target("deep"), ring());
    }

    /** Item 10: content inserted above the target shifts it; the ring follows. */
    @Test
    void layoutShiftFollows() {
        load("basic.html");
        overlay("save", "Shift", null);
        double before = num(target("save"), "t");
        browser.findElement(By.id("grow")).click();
        frames(3);
        assertTrue(num(target("save"), "t") > before + 150, "fixture did not shift");
        assertSameBox(target("save"), ring());
    }

    /** Item 4: the page replaces the target node; the runtime re-anchors on the new node. */
    @Test
    void rerenderReanchors() {
        load("basic.html");
        overlay("save", "Re-render", null);
        browser.findElement(By.id("grow")).click();
        browser.findElement(By.id("rerender")).click();
        frames(3);
        assertSameBox(target("save"), ring());
        assertEquals("visible", ring().get("vis"));
    }

    /** Item 10: a target scrolled out of its container hides the ring and the callout. */
    @Test
    void targetScrolledOutIsHidden() {
        load("nested-scroll.html");
        String id = overlay("deep", "Hidden until scrolled", null);
        frames(2);
        assertEquals("hidden", ring().get("vis"));
        assertEquals("hidden", placed(id).get("vis"));
    }

    /** Item 10: a partly visible target clips the ring to the container. */
    @Test
    void partiallyVisibleIsClipped() {
        load("nested-scroll.html");
        overlay("deep", "Half", null);
        browser.executeScript("document.getElementById('outer').scrollTop=100; document.getElementById('inner').scrollTop=135;");
        frames(2);
        Map<String, Object> ring = ring();
        assertEquals("visible", ring.get("vis"));
        assertTrue(String.valueOf(ring.get("clip")).startsWith("inset("), "ring not clipped: " + ring);
    }

    /** Item 3: the theme comes from constructable stylesheets, so a strict CSP with Trusted Types still styles it. */
    @Test
    void strictCspStylesApply() {
        load("strict-csp.html");
        String id = overlay("save", "Strict", null);
        frames(2);
        assertTrue(String.valueOf(ring().get("shadow")).contains("59, 130, 246"), String.valueOf(ring()));
        Object radius = browser.executeScript("return getComputedStyle(document.querySelector('[data-pkb-id=\"" + id + "\"]')).borderTopRightRadius");
        assertEquals("8px", radius);
    }

    /** Item 5: rings over a modal dialog sit in the top layer above it and still pass clicks through. */
    @Test
    void topLayerAboveDialog() {
        load("spa.html");
        browser.findElement(By.id("open-modal")).click();
        overlay("modal-ok", "Press OK", null);
        frames(2);
        assertEquals(true, browser.executeScript("return document.querySelector('[data-pkb-layer]').matches(':popover-open')"));
        assertSameBox(target("modal-ok"), ring());
        Object hit = browser.executeScript("var r=document.getElementById('modal-ok').getBoundingClientRect();"
                + "return document.elementFromPoint(r.left+r.width/2, r.top+r.height/2).id;");
        assertEquals("modal-ok", hit);
        assertFalse(String.valueOf(ring().get("vis")).equals("hidden"));
    }
}
