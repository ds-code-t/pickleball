package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.chrome.ChromeDriver;
import tools.dscode.common.composite.CompositeRuntime;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @Disabled("P1: audit input capture (isolated-world events never drained)")
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
    @Disabled("P1: audit item 6 (stale isolated-world context after navigation)")
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
}
