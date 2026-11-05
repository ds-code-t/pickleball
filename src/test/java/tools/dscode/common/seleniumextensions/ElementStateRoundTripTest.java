package tools.dscode.common.seleniumextensions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.composite.CompositeElements;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.composite.CompositeWebElement;
import tools.dscode.common.composite.ElementOperations;
import tools.dscode.common.composite.ElementState;
import tools.dscode.common.domoperations.elementstates.BinaryStateConditions;
import tools.dscode.common.domoperations.elementstates.BlankElementConditions;
import tools.dscode.common.domoperations.elementstates.CollapsedExpandedConditions;
import tools.dscode.common.domoperations.elementstates.EnabledDisabledConditions;
import tools.dscode.common.domoperations.elementstates.RequiredInputConditions;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State checks after a snapshot are the same booleans as the old self::* find,
 * and they do not call find again. Before this change each check was one extra
 * find. After, the find count is zero.
 */
class ElementStateRoundTripTest {
    private HtmlUnitDriver driver;
    private Path logDir;

    @BeforeEach
    void setUp() throws Exception {
        logDir = Files.createTempDirectory("element-state-log");
        CompositeRuntime.use(
                Path.of("target", "element-state-pin"),
                Map.of(
                        "pkb_composite", "off",
                        "pkb_composite_log", "state",
                        "pkb_composite_log_dir", logDir.toString()
                ),
                new ClassLoader(null) {
                },
                Path.of("target", "element-state-pin", "home")
        );
        ElementOperations.resetStateMeasurementsForTests();
        driver = new HtmlUnitDriver(true);
        URL page = ElementStateRoundTripTest.class.getResource("/element-state/states.html");
        driver.get(Objects.requireNonNull(page, "states").toString());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.quit();
        CompositeRuntime.resetForTests();
    }

    @Test
    void snapshotReadMatchesTheSelfFindAndAddsNoFind() throws Exception {
        String[] ids = {
                "off-button", "on-disabled", "host", "child-disabled", "needed",
                "blank", "filled", "empty-input", "closed", "open", "plain",
                "box", "empty-box", "pressed", "opt"
        };
        for (String id : ids) {
            WebElement live = driver.findElement(By.id(id));
            Oracle oracle = oracle(live);
            ElementSnapshot shot = SeleniumSnapshotProducer.INSTANCE.snapshot(driver, live);
            assertNotNull(shot.elementState(), id);
            assertEquals(oracle.disabled, shot.elementState().disabled(), id + " disabled");
            assertEquals(oracle.blank, shot.elementState().blank(), id + " blank");
            assertEquals(oracle.required, shot.elementState().required(), id + " required");
            assertEquals(oracle.expanded, shot.elementState().expanded(), id + " expanded");
            assertEquals(oracle.on, onOf(shot.elementState()), id + " on");

            ElementOperations.resetFindCallsForTests();
            int beforeFallbacks = ElementOperations.stateFallbacksForTests();
            assertEquals(oracle.disabled, EnabledDisabledConditions.isDisabled(live), id);
            assertEquals(!oracle.disabled, EnabledDisabledConditions.isEnabled(live), id);
            assertEquals(oracle.blank, BlankElementConditions.isBlank(live), id);
            assertEquals(oracle.required, RequiredInputConditions.isElementRequired(live), id);
            assertEquals(oracle.expanded, CollapsedExpandedConditions.isExpandedState(live), id);
            assertEquals(!oracle.expanded, CollapsedExpandedConditions.isCollapsedState(live), id);
            assertEquals(oracle.on, BinaryStateConditions.isCheckedSelectedOrOn(live), id);
            assertEquals(0, ElementOperations.findCallsForTests(), id + " extra finds; before was 1 find per check");
            assertEquals(beforeFallbacks, ElementOperations.stateFallbacksForTests(), id);
        }
        Path log = logFile();
        if (Files.isRegularFile(log)) {
            assertFalse(Files.readString(log).contains("snapshot miss"), Files.readString(log));
        }
    }

    @Test
    void compositeSnapshotMatchesSeleniumAndAddsNoFind() {
        WebElement live = driver.findElement(By.id("host"));
        ElementSnapshot selenium = SeleniumSnapshotProducer.INSTANCE.snapshot(driver, live);
        CompositeWebElement hit = CompositeElements.of("host", "div", "STALE", 1, 2, 3, 4);
        ElementSnapshot composite = CompositeSnapshotProducer.INSTANCE.snapshot(driver, hit);
        assertEquals(selenium.elementState(), composite.elementState());
        ElementOperations.resetFindCallsForTests();
        assertTrue(EnabledDisabledConditions.isDisabled(hit));
        assertFalse(BlankElementConditions.isBlank(hit));
        assertEquals(0, ElementOperations.findCallsForTests());
        assertEquals(0, ElementOperations.stateFallbacksForTests());
    }

    @Test
    void describeRemembersStateForALaterCheck() {
        WebElement live = driver.findElement(By.id("needed"));
        ElementOperations.resetStateMeasurementsForTests();
        var views = ElementOperations.describe(driver, List.of(live));
        assertEquals(1, views.size());
        assertNotNull(views.get(0).elementState());
        assertTrue(views.get(0).elementState().required());
        ElementOperations.resetFindCallsForTests();
        assertTrue(RequiredInputConditions.isElementRequired(live));
        assertEquals(0, ElementOperations.findCallsForTests(), "before: 1 self::* find; after: 0");
    }

    @Test
    void missingSnapshotFallsBackOnceAndLogs() throws Exception {
        WebElement live = driver.findElement(By.id("on-disabled"));
        ElementOperations.resetStateMeasurementsForTests();
        assertTrue(EnabledDisabledConditions.isDisabled(live));
        assertEquals(1, ElementOperations.findCallsForTests());
        assertEquals(1, ElementOperations.stateFallbacksForTests());
        String log = Files.readString(logFile());
        assertTrue(log.contains("snapshot miss fallback check=disabled"), log);
    }

    private Path logFile() {
        String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        return logDir.resolve("composite-" + day + ".log");
    }

    private static boolean onOf(ElementState state) {
        return state.selectable() ? state.domSelected() : state.attributeOn();
    }

    private static Oracle oracle(WebElement element) {
        return new Oracle(
                matches(element, EnabledDisabledConditions.disabledSelfXPath()),
                matches(element, BlankElementConditions.blankSelfXPath()),
                matches(element, RequiredInputConditions.requiredSelfXPath()),
                matches(element, CollapsedExpandedConditions.expandedSelfXPath()),
                selectable(element)
                        ? element.isSelected()
                        : matches(element, BinaryStateConditions.onSelfXPath())
        );
    }

    private static boolean matches(WebElement element, String xpath) {
        return !element.findElements(By.xpath(xpath)).isEmpty();
    }

    private static boolean selectable(WebElement element) {
        String tag = element.getTagName();
        if (tag != null && tag.equalsIgnoreCase("option")) return true;
        if (tag != null && tag.equalsIgnoreCase("input")) {
            String type = element.getAttribute("type");
            return "checkbox".equalsIgnoreCase(type) || "radio".equalsIgnoreCase(type);
        }
        return false;
    }

    private record Oracle(boolean disabled, boolean blank, boolean required, boolean expanded, boolean on) {
    }
}
