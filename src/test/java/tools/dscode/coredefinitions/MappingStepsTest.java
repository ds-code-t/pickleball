package tools.dscode.coredefinitions;

import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.NodeMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MappingStepsTest {

    @Test
    void mapValuesUsesFirstColumnAsKeyAndSecondColumnAsActualValue() {
        NodeMap runMap = new NodeMap();

        MappingSteps.mapValues(
            runMap,
            List.of(
                List.of("numericStatus", "200"),
                List.of("textStatus", "\"200\""),
                List.of("enabled", "true"),
                List.of("label", "inventory"),
                List.of("payload", "{\"id\":73}")
            )
        );

        assertEquals(200, runMap.get("numericStatus"));
        assertEquals("200", runMap.get("textStatus"));
        assertEquals(true, runMap.get("enabled"));
        assertEquals("inventory", runMap.get("label"));
        assertEquals(73, runMap.get("payload.id"));
    }

    @Test
    void resolvedTemplateTextRetainsItsResolvedJsonType() {
        NodeMap runMap = new NodeMap();

        // A template such as <response.statusCode> is resolved by the running
        // parsing map before MAP VALUES receives its DataTable. At that point
        // the cell value is the resolved text "200".
        MappingSteps.mapValues(
            runMap,
            List.of(List.of("copiedStatus", "200"))
        );

        assertEquals(200, runMap.get("copiedStatus"));
    }

    @Test
    void mapValuesRequiresExactlyTwoColumns() {
        NodeMap runMap = new NodeMap();

        assertThrows(
            IllegalArgumentException.class,
            () -> MappingSteps.mapValues(
                runMap,
                List.of(List.of("onlyOneColumn"))
            )
        );
    }

    @Test
    void mapValuesRequiresANonBlankKey() {
        NodeMap runMap = new NodeMap();

        assertThrows(
            IllegalArgumentException.class,
            () -> MappingSteps.mapValues(
                runMap,
                List.of(List.of(" ", "200"))
            )
        );
    }
}
