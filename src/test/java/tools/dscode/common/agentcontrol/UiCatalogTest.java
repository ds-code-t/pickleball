package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiCatalogTest {
    @Test
    @SuppressWarnings("unchecked")
    void everyExampleValidates() {
        UiCatalog.components().forEach((name, component) -> assertDoesNotThrow(() -> {
            Map<String, Object> op = AgentStore.JSON.readValue(component.example(), Map.class);
            UiBatch.validate(1, op);
        }, name));
    }

    @Test
    void everyVerbMapsToAComponent() {
        Set<String> verbs = new HashSet<>();
        UiCatalog.components().values().forEach(component -> verbs.add(component.verb()));
        for (String verb : UiCatalog.coveredVerbs()) {
            boolean removal = verb.equals("overlay-remove") || verb.startsWith("un");
            assertTrue(removal ? UiCatalog.component("remove") != null : verbs.contains(verb), verb);
        }
        for (String visual : List.of("overlay", "label", "border", "arrow", "step-badge", "magnifier", "spotlight")) {
            assertTrue(UiCatalog.components().get("remove").options().get("of").help().contains(visual), visual);
        }
    }

    @Test
    void catalogJsonIsSmall() throws Exception {
        int size = AgentStore.JSON.writeValueAsString(UiCatalog.describe(null)).length();
        assertTrue(size < 6 * 1024, "ui-catalog JSON is " + size + " bytes");
    }
}
