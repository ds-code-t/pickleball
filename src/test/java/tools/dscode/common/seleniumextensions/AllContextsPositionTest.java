package tools.dscode.common.seleniumextensions;

import com.xpathy.XPathy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.composite.CompositeElements;
import tools.dscode.common.domoperations.NestedByLocator;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug 4. All-contexts used to keep the first hit for any position other than
 * {@code last}. The 3rd button is now the third, and only that hit is wrapped.
 */
class AllContextsPositionTest {
    private HtmlUnitDriver driver;

    @BeforeEach
    void setUp() {
        SnapshotPages.pinSelenium();
        driver = new HtmlUnitDriver(true);
        driver.get(AllContextsPositionTest.class.getResource("/position/ordinals.html").toString());
        ElementWrapper.resetWrapsForTests();
        ElementSnapshotScript.resetScriptCallsForTests();
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.quit();
        SnapshotPages.resetPin();
    }

    @Test
    void theThirdButtonMatchesSeleniumAndWrapsOnce() {
        ElementMatch match = ElementMatch.resolved("Button");
        match.elementPosition = "3";
        match.selectionType = "";
        match.elementIndex = 3;
        match.xPathy = XPathy.from("//button[@class='ord']");

        List<org.openqa.selenium.WebElement> selenium = NestedByLocator.findWithRetry(
                driver, By.xpath("//button[@class='ord']"), match);
        assertEquals("Three", selenium.getFirst().getText());

        ElementWrapper.resetWrapsForTests();
        ElementSnapshotScript.resetScriptCallsForTests();
        List<ElementWrapper> scanned = AllContextsScan.find(driver, match);
        assertEquals(1, scanned.size());
        assertEquals("Three", scanned.getFirst().getText());
        assertEquals(1, ElementWrapper.wrapsForTests(), "all-contexts wrapped more than the chosen hit");
        assertEquals(1, ElementSnapshotScript.scriptCallsForTests(), "all-contexts snapshotted more than the chosen hit");
    }

    @Test
    void compositeHitDefersEvalUntilAPositionFieldIsRead() {
        driver.get(AllContextsPositionTest.class.getResource("/element-snapshot/fixture.html").toString());
        ElementWrapper wrapper = ElementWrapper.forResolvedElement(
                driver, CompositeElements.of("target", "div", "STALE-CACHED", 8, 9, 10, 11));
        assertEquals(0, ElementSnapshotScript.scriptCallsForTests());
        assertEquals("div", wrapper.getTagName());
        assertEquals("STALE-CACHED", wrapper.getSnapshotValue("text"));
        assertEquals("target", wrapper.getSnapshotValue("id"));
        assertEquals(8, wrapper.getRect().getX());
        assertEquals(0, ElementSnapshotScript.scriptCallsForTests());
        assertEquals(3, wrapper.getIndex());
        assertEquals(1, ElementSnapshotScript.scriptCallsForTests());
        assertEquals("#target", wrapper.snapshot().stableSelector());
        assertEquals("sv", wrapper.snapshot().selectedValue());
        assertEquals(1, ElementSnapshotScript.scriptCallsForTests());
        assertTrue(wrapper.snapshot().text().contains("Hello"), wrapper.snapshot().text());
    }
}
