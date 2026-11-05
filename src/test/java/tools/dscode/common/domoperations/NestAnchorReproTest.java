package tools.dscode.common.domoperations;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.composite.CompositeWebElement;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug 3. {@code tryCompositeNestBatch} returns null when any hit has no id or name.
 * HtmlUnit has no CDP join. A primary binary must still keep the deepest Selenium node.
 */
class NestAnchorReproTest {
    @Test
    void nestingBatchIsSkippedWhenAnyHitLacksAnAnchor() throws Exception {
        HtmlUnitDriver driver = new HtmlUnitDriver(true);
        try {
            driver.get(NestAnchorReproTest.class.getResource("/position/ordinals.html").toString());
            List<WebElement> deepest = NestedByLocator.find(
                    driver, By.cssSelector("div.nest"), true, NestedByLocator.NestingMode.DEEPEST_ONLY);
            assertEquals(1, deepest.size());
            assertTrue(deepest.getFirst().getDomAttribute("class").contains("inner"), deepest.getFirst().getDomAttribute("class"));
        } finally {
            driver.quit();
        }

        WebElement anchored = hit("outer", "main:1");
        WebElement bare = hit(null, "main:2");
        assertNull(NestedByLocator.tryCompositeNestBatch(
                List.of(anchored, bare),
                NestedByLocator.NestingMode.DEEPEST_ONLY
        ));
    }

    private static WebElement hit(String id, String nodeId) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", nodeId);
        node.put("node_name", "div");
        node.put("text", "x");
        if (id != null) {
            node.putObject("attributes").put("id", id);
        }
        return CompositeWebElement.fromQuery("//div", node, 0);
    }
}
