package tools.dscode.browser;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomMutationsTest {
    @Test
    void optionsParseKindsAliasesFiltersAndDebounce() {
        DomMutations.Options o = DomMutations.Options.of(Map.of("kinds", "child-list,characterData", "attributes", "class, data-x",
                "debounce", "120ms", "selector", "#app", "text", "Ready"));
        assertEquals(List.of("childList", "text"), o.kinds());
        assertEquals(List.of("class", "data-x"), o.attributes());
        assertEquals(120, o.debounceMs());
        assertEquals("#app", o.selector());
        assertFalse(o.own());
        assertEquals(DomMutations.KINDS, DomMutations.Options.of(Map.of()).kinds());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> DomMutations.Options.of(Map.of("kinds", "net")));
        assertTrue(bad.getMessage().startsWith("error: unknown DOM change kind net"), bad.getMessage());
    }

    @Test
    void domKindsTellsDomWatchFromEventWatch() {
        assertTrue(DomMutations.domKinds("childList,attributes"));
        assertFalse(DomMutations.domKinds("network"));
        assertFalse(DomMutations.domKinds(""));
    }

    @Test
    void scriptCarriesConfigAndExcludesPickleballSurfaces() {
        String expression = DomMutations.bindingExpression(DomMutations.Options.defaults(), "w1", "pkbDomWatch_w1");
        assertTrue(expression.contains("\"id\":\"w1\""), expression);
        assertTrue(expression.contains("window['pkbDomWatch_w1']"), expression);
        assertTrue(DomMutations.function().contains("data-pkb"), "own surfaces are filtered in the page");
        assertTrue(DomMutations.disconnectExpression("w1").contains("__pkbDomWatch_w1"));
    }

    @Test
    void summaryAndViewDescribeABatch() {
        ObjectNode batch = DomMutations.parse("{\"id\":\"w\",\"seq\":2,\"records\":3,\"childList\":2,\"attributes\":1,\"text\":0,"
                + "\"added\":2,\"removed\":0,\"targets\":[\"ul#list\"],\"attributeNames\":[\"class\"],\"sample\":\"a b\"}");
        assertEquals("childList=2 attributes=1 added=2 in ul#list", DomMutations.summary(batch));
        Map<String, Object> view = DomMutations.view(batch);
        assertEquals("dom", view.get("kind"));
        assertEquals(List.of("class"), view.get("attributeNames"));
        assertEquals(null, DomMutations.parse("not json"));
    }
}
