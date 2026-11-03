package tools.dscode.common.seleniumextensions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeleniumSnapshotProducerTest {
    private HtmlUnitDriver driver;

    @BeforeEach
    void setUp() {
        SnapshotPages.pinSelenium();
        driver = SnapshotPages.open();
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.quit();
        SnapshotPages.resetPin();
    }

    @Test
    void positionsSelectedChildAttributesAndSelectorComeFromTheLiveElement() {
        ElementSnapshot shot = SeleniumSnapshotProducer.INSTANCE.snapshot(driver, SnapshotPages.byId(driver, "target"));

        assertEquals("div", shot.tag());
        assertEquals(3, shot.siblingIndex());
        assertEquals(1, shot.sameTagIndex());
        assertEquals("sv", shot.selectedValue());
        assertEquals("target", shot.attribute("id"));
        assertEquals("target", shot.attribute("data-testid"));
        assertEquals("targetName", shot.attribute("name"));
        assertEquals("Target Title", shot.attribute("title"));
        assertTrue(shot.text().contains("Hello"), shot.text());
        assertTrue(shot.text().contains("Sel"), shot.text());
        assertEquals("#target", shot.stableSelector());
        assertNull(shot.nodeId());
        assertNull(shot.bounds());
    }

    @Test
    void selectReadsOptionTextAndSelectedChildValue() {
        ElementSnapshot shot = SeleniumSnapshotProducer.INSTANCE.snapshot(driver, SnapshotPages.byId(driver, "account"));

        assertEquals("select", shot.tag());
        assertEquals("Premium", shot.text());
        assertEquals("pre", shot.selectedValue());
        assertEquals("pre", shot.value());
        assertEquals("#account", shot.stableSelector());
    }

    @Test
    void inputReadsTheValueAttributeAndAStableNameOrId() {
        ElementSnapshot shot = SeleniumSnapshotProducer.INSTANCE.snapshot(driver, SnapshotPages.byId(driver, "qty"));

        assertEquals("input", shot.tag());
        assertEquals("3", shot.text());
        assertEquals("3", shot.value());
        assertEquals("3", shot.attribute("value"));
        assertEquals("quantity", shot.attribute("name"));
        assertNull(shot.selectedValue());
        assertEquals("#qty", shot.stableSelector());
    }
}
