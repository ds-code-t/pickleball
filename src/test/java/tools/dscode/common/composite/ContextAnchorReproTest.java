package tools.dscode.common.composite;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.domoperations.NestedByLocator;
import tools.dscode.common.domoperations.PositionSelection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bugs 1 and 2, plus the shared ordinal slice.
 * Selenium searches under a context that has no id. Composite {@code scope()}
 * returns null, so the find is empty. An XPath that starts with {@code (}
 * is returned unchanged, so it searches the whole page. Phase B scopes both
 * with {@code --within}. These assertions are the repro of the current gap.
 */
class ContextAnchorReproTest {
    @Test
    void contextWithoutIdIsAnEmptyCompositeFind() {
        HtmlUnitDriver driver = new HtmlUnitDriver(true);
        try {
            driver.get(ContextAnchorReproTest.class.getResource("/position/ordinals.html").toString());
            WebElement host = driver.findElement(By.cssSelector("div.panel"));
            assertTrue(host.getDomAttribute("id") == null || host.getDomAttribute("id").isBlank());
            assertEquals("inside", host.findElement(By.xpath(".//span")).getText());
        } finally {
            driver.quit();
        }

        CompositeWebElement bare = element(null);
        assertNull(bare.anchor());
        assertEquals(List.of(), bare.findElements(By.xpath(".//span")));
    }

    @Test
    void parenthesizedXPathIsNotScopedToTheContext() {
        CompositeWebElement host = element("panel");
        By raw = By.xpath("(//span[@class='child'])[1]");
        assertSame(raw, host.scope(raw));
        By relative = NestedByLocator.withinElementLocator(raw);
        assertTrue(relative.toString().contains(".//"), relative.toString());
        assertTrue(host.scope(By.xpath(".//span")).toString().contains("@id='panel'"));
    }

    @Test
    void ordinalSliceUsesTheSharedIndex() {
        ObjectNode json = JsonNodeFactory.instance.objectNode();
        var nodes = json.putArray("nodes");
        nodes.add(node("a"));
        nodes.add(node("b"));
        nodes.add(node("c"));
        CompositeSelector selector = new CompositeSelector(null, null, null, "web", 2, "third");
        List<WebElement> chosen = DomCompositeAdapter.get().hits(json, selector);
        assertEquals(1, chosen.size());
        assertEquals("c", chosen.getFirst().getText());
        Integer ordinal = SelectorTranslator.ordinal(ElementPhrase.of("Button", "Save").ordinal("3rd"));
        assertEquals(List.of("c"), PositionSelection.applyOrdinal(List.of("a", "b", "c"), ordinal));
        assertEquals(List.of("c"), PositionSelection.applyOrdinal(List.of("a", "b", "c"), -1));
        assertEquals(List.of(), PositionSelection.applyOrdinal(List.of("a", "b"), 9));
    }

    private static CompositeWebElement element(String id) {
        return (CompositeWebElement) CompositeWebElement.fromQuery("//div", node(id == null ? "bare" : id, id), 0);
    }

    private static ObjectNode node(String text) {
        return node(text, null);
    }

    private static ObjectNode node(String text, String id) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", "main:" + text);
        node.put("node_name", "div");
        node.put("text", text);
        if (id != null) {
            node.putObject("attributes").put("id", id);
        }
        return node;
    }
}
