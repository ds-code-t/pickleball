package tools.dscode.common.seleniumextensions;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.assertions.ValueWrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementWrapperSnapshotTest {
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
    void constructionPublishesSnapshotGettersAndCachesTheReturnValue() {
        WebElement raw = SnapshotPages.byId(driver, "target");
        ElementWrapper wrapper = ElementWrapper.forResolvedElement(driver, raw);

        assertEquals("div", wrapper.getTagName());
        assertEquals(3, wrapper.getIndex());
        assertEquals(1, wrapper.getSameTagIndex());
        assertEquals("sv", wrapper.getSnapshotValue("childValue"));
        assertEquals("sv", wrapper.getSnapshotValue("selectedValue"));
        assertEquals("#target", wrapper.getSnapshotValue("stableSelector"));
        assertEquals("3", wrapper.getSnapshotValue("siblingIndex"));
        assertEquals("target", wrapper.getSnapshotAttribute("id"));
        assertTrue(wrapper.snapshotContainsAnyAttribute("data-testid", "missing"));
        assertTrue(wrapper.snapshotAttributeEqualsIgnoreCase("name", "TARGETNAME"));
        assertFalse(wrapper.snapshotAttributeEqualsIgnoreCase("name", "other"));

        ObjectNode json = wrapper.getAttributeSnapshot();
        assertEquals("div", json.get("tagName").asText());
        assertEquals("sv", json.get("childValue").asText());
        assertEquals(3, json.get("siblingIndex").asInt());
        assertFalse(json.has("_elementReturnValue"));

        ValueWrapper first = wrapper.getElementReturnValue();
        assertEquals("sv", first.toString());
        assertEquals("sv", wrapper.getElementReturnValue().toString());
        assertEquals("sv", wrapper.getAttributeSnapshot().get("_elementReturnValue").asText());
        assertEquals("#target", wrapper.snapshot().stableSelector());
    }

    @Test
    void plainTextUsesTextContentWhenThereIsNoSelectedChild() {
        ElementWrapper wrapper = ElementWrapper.forResolvedElement(driver, SnapshotPages.byId(driver, "sib-a"));
        assertEquals("span", wrapper.getTagName());
        assertEquals(0, wrapper.getIndex());
        assertEquals("A", wrapper.getElementReturnValue().toString());
    }
}
