package tools.dscode.common.composite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebElement;
import tools.dscode.common.domoperations.ExecutionDictionary;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XPathFallbackTest {
    @AfterEach
    void reset() {
        CompositeLog.resetForTests();
        CompositeRuntime.resetForTests();
    }

    @Test
    void pureXPathCategoryWarnsOnceAndStillResolves() {
        ExecutionDictionary dict = new ExecutionDictionary() {
        };
        dict.category("Widget").addBase("//div[@id='w']");
        ExecutionDictionary.CategoryResolution first = dict.getFinalCategoryResolution("Widget", null, null);
        assertTrue(first.xpath().getXpath().contains("div"), first.xpath().getXpath());
        assertNull(first.combined());
        String fallback = CompositeLog.lastFallbackForTests();
        assertTrue(fallback.contains("xpath-definition:Widget"), String.valueOf(fallback));
        dict.getFinalCategoryResolution("Widget", null, null);
        assertEquals(1, CompositeLog.linesForTests().stream().filter(line -> line.contains("xpath-definition:Widget")).count(),
                CompositeLog.linesForTests().toString());
    }

    @Test
    void categoryAndXPathPhraseKeepsBoth() {
        CompositeSelector selector = SelectorTranslator.phrase(ElementPhrase.of("Button", "Save").xpath("//button[@id='save']"));
        assertEquals("//button[@id='save']", selector.xpath());
        assertEquals("(role=button AND text=\"Save\")", selector.pipeline());
    }

    @Test
    void combinedByDelegatesToXPathAndRoundTrips() {
        List<By> seen = new ArrayList<>();
        SearchContext context = new SearchContext() {
            @Override
            public List<WebElement> findElements(By by) {
                seen.add(by);
                return List.of();
            }

            @Override
            public WebElement findElement(By by) {
                seen.add(by);
                return null;
            }
        };
        By combined = CombinedBy.of("(role=button AND text=\"Save\")", "//button[@id='save']");
        assertTrue(combined instanceof CombinedBy);
        assertEquals("(role=button AND text=\"Save\")", ((CombinedBy) combined).combined());
        assertEquals("//button[@id='save']", ((CombinedBy) combined).xpath());
        assertEquals("By.xpath: //button[@id='save']", combined.toString());
        combined.findElements(context);
        assertEquals(1, seen.size());
        assertEquals("By.xpath: //button[@id='save']", seen.get(0).toString());

        By plain = CombinedBy.of("  ", "//div");
        assertEquals("By.xpath: //div", plain.toString());
        assertTrue(!(plain instanceof CombinedBy));

        CompositeSelector selector = SelectorTranslator.fromBy(combined);
        assertEquals("//button[@id='save']", selector.xpath());
        assertEquals("(role=button AND text=\"Save\")", selector.pipeline());
    }

    @Test
    void fiveArgumentResolutionStillHasNoCombinedClause() {
        ExecutionDictionary.CategoryResolution resolution = new ExecutionDictionary.CategoryResolution(
                "Button", null, null, null, Set.of());
        assertNull(resolution.combined());
    }
}
