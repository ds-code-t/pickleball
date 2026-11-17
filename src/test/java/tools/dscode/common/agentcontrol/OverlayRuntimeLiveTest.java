package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Keys;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
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

    // ---- P3: panel in the page ----

    private Map<String, Object> openPanel(Map<String, String> extra) {
        Map<String, String> args = new LinkedHashMap<>(extra);
        args.put("target", "page");
        AgentControl.Result opened = run("panel-open", args);
        assertEquals(0, opened.exit(), String.valueOf(opened.body()));
        assertEquals("page", opened.body().get("target"));
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) opened.body().get("panel");
        return state;
    }

    private SearchContext layerRoot() {
        return browser.findElement(By.cssSelector("[data-pkb-layer]")).getShadowRoot();
    }

    private WebElement inPanel(String css) {
        return layerRoot().findElement(By.cssSelector(css));
    }

    private Map<String, Object> panelRect() {
        return rect(RING.replace(".ring", "[data-pkb-panel]"));
    }

    /** Presses at a viewport point and drags to another, in two pointer moves. */
    private void dragTo(int fromX, int fromY, int toX, int toY) {
        new Actions(browser).moveToLocation(fromX, fromY).clickAndHold()
                .moveToLocation((fromX + toX) / 2, (fromY + toY) / 2)
                .moveToLocation(toX, toY).release().perform();
        frames(2);
    }

    private int[] pointIn(String css, int dx, int dy) {
        Map<String, Object> box = rect(RING.replace(".ring", css));
        assertNotNull(box, css);
        return new int[]{(int) Math.round(num(box, "l") + dx), (int) Math.round(num(box, "t") + dy)};
    }

    /** Item 1: the header drags the panel by the pointer delta; dragging past the edge clamps it inside the viewport. */
    @Test
    void panelDragsAndClamps() {
        load("basic.html");
        openPanel(Map.of());
        Map<String, Object> before = panelRect();
        int[] grab = pointIn(".pkb-panel .pkb-head", 30, 18);
        // Headless Chrome's viewport is shorter than its 800 px window, so the upward move stays on screen.
        dragTo(grab[0], grab[1], grab[0] - 400, grab[1] - 250);
        Map<String, Object> moved = panelRect();
        assertEquals(num(before, "l") - 400, num(moved, "l"), 1.01, moved.toString());
        assertEquals(num(before, "t") - 250, num(moved, "t"), 1.01, moved.toString());
        int[] again = pointIn(".pkb-panel .pkb-head", 30, 18);
        dragTo(again[0], again[1], 2, 2);
        Map<String, Object> clamped = panelRect();
        assertEquals(0.0, num(clamped, "l"), 1.01, clamped.toString());
        assertEquals(0.0, num(clamped, "t"), 1.01, clamped.toString());
        OverlayFixtures.shot(browser, "p3-panel-dragged");
    }

    /** Item 1: the corner grip resizes; the minimum size holds. */
    @Test
    void panelResizes() {
        load("basic.html");
        openPanel(Map.of("x", "100", "y", "100"));
        Map<String, Object> before = panelRect();
        int[] corner = pointIn(".pkb-grip[data-dir=se]", 7, 7);
        dragTo(corner[0], corner[1], corner[0] + 120, corner[1] + 80);
        Map<String, Object> grown = panelRect();
        assertEquals(num(before, "w") + 120, num(grown, "w"), 1.01, grown.toString());
        assertEquals(num(before, "h") + 80, num(grown, "h"), 1.01, grown.toString());
        int[] small = pointIn(".pkb-grip[data-dir=se]", 7, 7);
        dragTo(small[0], small[1], 110, 110);
        Map<String, Object> smallest = panelRect();
        assertEquals(260.0, num(smallest, "w"), 1.01, smallest.toString());
        assertEquals(200.0, num(smallest, "h"), 1.01, smallest.toString());
        OverlayFixtures.shot(browser, "p3-panel-resized");
    }

    /** Item 2 in the page: Enter sends, Shift+Enter adds a line, and the bubble shows both lines. */
    @Test
    void enterSendsShiftEnterNewlines() throws Exception {
        load("basic.html");
        openPanel(Map.of());
        WebElement text = inPanel(".pkb-composer textarea");
        text.click();
        text.sendKeys("hello");
        text.sendKeys(Keys.chord(Keys.SHIFT, Keys.ENTER));
        text.sendKeys("world");
        text.sendKeys(Keys.ENTER);
        frames(1);
        assertEquals("", text.getAttribute("value"));
        Object bubble = browser.executeScript(RING.replace(".ring", ".pkb-msg[data-from=human]")
                + " return n ? n.lastChild.textContent : null;");
        assertEquals("hello\nworld", bubble);
        AgentControl.Result history = run("chat-history", Map.of());
        assertTrue(String.valueOf(history.body().get("messages")).contains("hello\nworld"), String.valueOf(history.body()));
    }

    /** Item 1: a page hotkey on "s" that calls preventDefault cannot eat the letters typed in the panel. */
    @Test
    void pageHotkeysDoNotStealTyping() {
        load("basic.html");
        openPanel(Map.of());
        WebElement text = inPanel(".pkb-composer textarea");
        text.click();
        text.sendKeys("sss");
        assertEquals("sss", text.getAttribute("value"));
        assertEquals(0L, ((Number) browser.executeScript("return window.hotkeys")).longValue());
    }

    /** The human message lands in the run chat as source=human, and the bubble turns from pending to sent. */
    @Test
    void humanMessageReachesChatLog() throws Exception {
        load("basic.html");
        openPanel(Map.of());
        WebElement text = inPanel(".pkb-composer textarea");
        text.click();
        text.sendKeys("from the page");
        inPanel(".pkb-composer button").click();
        AgentControl.Result polled = run("chat-poll", Map.of("wait", "5"));
        assertEquals(0, polled.exit(), String.valueOf(polled.body()));
        String messages = String.valueOf(polled.body().get("messages"));
        assertTrue(messages.contains("from the page") && messages.contains("source=human"), messages);
        Object state = browser.executeScript(RING.replace(".ring", ".pkb-msg[data-from=human]") + " return n ? n.getAttribute('data-state') : null;");
        assertEquals("sent", state);
    }

    /** An agent chat-send reaches the open page panel as well as the run chat. */
    @Test
    void agentMessageAppearsInPanel() throws Exception {
        load("basic.html");
        openPanel(Map.of());
        AgentControl.Result sent = run("chat-send", Map.of("text", "Next, open Settings."));
        assertEquals(0, sent.exit(), String.valueOf(sent.body()));
        frames(1);
        Object bubble = browser.executeScript(RING.replace(".ring", ".pkb-msg[data-from=agent]") + " return n ? n.textContent : null;");
        assertTrue(String.valueOf(bubble).contains("Next, open Settings."), String.valueOf(bubble));
        OverlayFixtures.shot(browser, "p3-panel-chat");
        AgentControl.Result closed = run("panel-close", Map.of("target", "page"));
        assertEquals(0, closed.exit(), String.valueOf(closed.body()));
        assertEquals(0L, ((Number) browser.executeScript("return document.querySelectorAll('[data-pkb-layer]').length")).longValue());
    }

    // ---- P4: guided steps ----

    private static final String THREE_STEPS = "[{\"t\":\"#email\",\"say\":\"Type your email\",\"do\":\"input\",\"capture\":true},"
            + "{\"t\":\"#password\",\"say\":\"Type your password\",\"do\":\"input\",\"capture\":true},"
            + "{\"t\":\"#save\",\"say\":\"Click Save\",\"do\":\"click\"}]";

    private AgentControl.Result startGuide(String steps) {
        AgentControl.Result started = run("guide", Map.of("steps", steps));
        assertEquals(0, started.exit(), String.valueOf(started.body()));
        return started;
    }

    /** Badge states read from the layer: "current", "done" or "todo" per step number. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stateFromBadges() {
        return (List<Map<String, Object>>) browser.executeScript("var l=document.querySelector('[data-pkb-layer]'); if(!l) return [];"
                + "return Array.from(l.shadowRoot.querySelectorAll('.badge')).map(function(b,i){ return {n:i+1, state:b.className.replace('badge ','')}; });");
    }

    private String badge(int n) {
        return String.valueOf(stateFromBadges().get(n - 1).get("state"));
    }

    /** Waits (up to five seconds) for step n's badge to reach the state; typing settles before a step completes. */
    private void awaitBadge(int n, String state) {
        long end = System.currentTimeMillis() + 5000;
        while (!state.equals(badge(n)) && System.currentTimeMillis() < end) frames(1);
        assertEquals(state, badge(n), "step " + n);
    }

    /** Item 7: typing into the step-1 field completes step 1 and makes step 2 current. */
    @Test
    void guideAdvancesOnInput() throws Exception {
        load("basic.html");
        AgentControl.Result started = startGuide(THREE_STEPS);
        assertEquals(3, ((Number) started.body().get("steps")).intValue());
        assertEquals("[]", String.valueOf(started.body().get("miss")));
        assertEquals("current", badge(1));
        assertEquals("todo", badge(2));
        browser.findElement(By.id("email")).sendKeys("me@example.test");
        assertEquals("current", badge(1), "one keystroke must not complete the step");
        awaitBadge(1, "done");
        assertEquals("current", badge(2));
        OverlayFixtures.shot(browser, "p4-guide-step2");
        String events = String.valueOf(run("overlay-poll", Map.of()).body().get("events"));
        assertTrue(events.contains("type=step") && events.contains("s=1") && events.contains("me@example.test"), events);
    }

    /** Item 7: a click step completes on the click; the last step closes the guide with a guide-done event. */
    @Test
    void guideClickStep() {
        load("basic.html");
        startGuide("[{\"t\":\"#save\",\"say\":\"Click Save\",\"do\":\"click\"}]");
        browser.findElement(By.id("save")).click();
        frames(2);
        assertEquals("done", badge(1));
        String events = String.valueOf(run("overlay-poll", Map.of()).body().get("events"));
        assertTrue(events.contains("how=click") && events.contains("type=guide"), events);
        AgentControl.Result done = run("guide-done", Map.of());
        assertEquals(0, done.exit(), String.valueOf(done.body()));
        assertTrue(stateFromBadges().isEmpty(), "guide visuals left behind");
    }

    /** Password values never reach the event file, even with capture:true. */
    @Test
    void passwordValueNeverRecorded() throws Exception {
        load("basic.html");
        startGuide(THREE_STEPS);
        browser.findElement(By.id("email")).sendKeys("me@example.test");
        awaitBadge(2, "current");
        browser.findElement(By.id("password")).sendKeys("Hunter2-Secret!");
        awaitBadge(3, "current");
        AgentControl.Result polled = run("overlay-poll", Map.of());
        String events = String.valueOf(polled.body().get("events"));
        assertTrue(events.contains("s=2") && events.contains("filled=true"), events);
        Path agentDir = AgentStore.root(project, "overlay-live");
        try (var files = java.nio.file.Files.walk(agentDir)) {
            for (Path file : files.filter(java.nio.file.Files::isRegularFile).toList()) {
                String body = java.nio.file.Files.readString(file);
                assertFalse(body.contains("Hunter2"), "password text stored in " + file);
            }
        }
        OverlayFixtures.shot(browser, "p4-guide-step3");
    }

    /** The step-3 target is re-rendered by the page; its ring follows the new node. */
    @Test
    void guideSurvivesRerender() {
        load("basic.html");
        startGuide(THREE_STEPS);
        run("guide-next", Map.of());
        run("guide-next", Map.of());
        assertEquals("current", badge(3));
        browser.findElement(By.id("grow")).click();
        browser.findElement(By.id("rerender")).click();
        frames(3);
        Map<String, Object> ring = rect("var l=document.querySelector('[data-pkb-layer]'); var n=l&&l.shadowRoot.querySelectorAll('.ring')[2];");
        assertSameBox(target("save"), ring);
        AgentControl.Result back = run("guide-back", Map.of());
        assertEquals(0, back.exit(), String.valueOf(back.body()));
        assertEquals(2, ((Number) back.body().get("current")).intValue());
    }

    /** ask with choices returns the person's pick as an answer event. */
    @Test
    void askConfirmReturnsTheChoice() {
        load("basic.html");
        AgentControl.Result asked = run("ask", Map.of("say", "Use the Pro plan?", "choices", "Yes,No", "handle", "plan"));
        assertEquals(0, asked.exit(), String.valueOf(asked.body()));
        frames(2);
        OverlayFixtures.shot(browser, "p4-ask-confirm");
        layerRoot().findElement(By.cssSelector(".ask button.secondary")).click();
        String events = String.valueOf(run("overlay-poll", Map.of()).body().get("events"));
        assertTrue(events.contains("type=answer") && events.contains("value=No"), events);
    }

    // ---- P5: state and re-injection ----

    private boolean shown(String id) {
        return (Boolean) browser.executeScript("return !!document.querySelector('[data-pkb-id=\"' + arguments[0] + '\"]');", id);
    }

    private void awaitShown(String id, boolean expected) {
        long end = System.currentTimeMillis() + 5000;
        while (shown(id) != expected && System.currentTimeMillis() < end) frames(2);
        if (shown(id) != expected) {
            Path state = AgentStore.root(project, "overlay-live").resolve(UiState.FILE);
            String saved;
            try {
                saved = java.nio.file.Files.exists(state) ? "preload=" + UiState.read(state).path("preload").asText() + " layer=" + browser.executeScript("return !!document.querySelector('[data-pkb-layer]') + ' ' + document.readyState") : "(no state file)";
            } catch (java.io.IOException failure) {
                saved = failure.toString();
            }
            String live;
            try {
                live = PageSurfaces.runtimeCall("snapshot", null, false);
            } catch (RuntimeException failure) {
                live = failure.toString();
            }
            assertEquals(expected, shown(id), "placement " + id + " shown; page runtime: " + live + "; saved state: " + saved);
        }
    }

    /** Item 6: a reload brings back the callout, the guide at its step, and the moved panel. */
    @Test
    void reloadReinjects() {
        load("basic.html");
        String id = overlay("save", "Click Save when ready", null);
        startGuide(THREE_STEPS);
        assertEquals(0, run("guide-next", Map.of()).exit());
        openPanel(Map.of("x", "40", "y", "300"));
        Map<String, Object> before = panelRect();
        browser.navigate().refresh();
        awaitShown(id, true);
        long end = System.currentTimeMillis() + 5000;
        while (stateFromBadges().size() < 3 && System.currentTimeMillis() < end) frames(2);
        assertEquals("done", badge(1));
        assertEquals("current", badge(2));
        Map<String, Object> panel = panelRect();
        assertEquals(40, num(panel, "l"), 1.01, String.valueOf(panel));
        assertEquals(num(before, "t"), num(panel, "t"), 1.01, String.valueOf(panel));
        assertEquals(num(before, "w"), num(panel, "w"), 1.01, String.valueOf(panel));
        OverlayFixtures.shot(browser, "p5-after-reload");
    }

    /** Leaving the page shows nothing there; coming back restores the placement. */
    @Test
    void navigateAwayAndBack() {
        load("basic.html");
        String id = overlay("save", "Saved here", null);
        browser.get(fixtures.url("strict-csp.html"));
        frames(3);
        assertFalse(shown(id), "placement shown on another page");
        browser.navigate().back();
        awaitShown(id, true);
    }

    /** An SPA route change (pushState) withdraws page-scoped UI; the route back re-places it on the re-rendered target. */
    @Test
    void spaRouteScope() {
        load("spa.html?route=a");
        String id = overlay("a-action", "Route A only", null);
        browser.findElement(By.id("to-b")).click();
        awaitShown(id, false);
        browser.findElement(By.id("to-a")).click();
        awaitShown(id, true);
        assertSameBox(target("a-action"), ring());
    }

    /** html-cleanup removes the preload too: after a reload nothing comes back. */
    @Test
    void clearRemovesPreload() {
        load("basic.html");
        String id = overlay("save", "Gone after cleanup", null);
        AgentControl.Result cleaned = run("html-cleanup", Map.of("target", "page"));
        assertEquals(0, cleaned.exit(), String.valueOf(cleaned.body()));
        browser.navigate().refresh();
        frames(10);
        assertFalse(shown(id), "placement came back after cleanup");
        assertFalse(java.nio.file.Files.exists(AgentStore.root(project, "overlay-live").resolve(UiState.FILE)));
    }
}
