package io.cucumber.core.runner;

import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScenarioStepDataTest {

    @Test
    void resolutionMapUsesExternalPassedStoredPassedThenExamples() {
        ParsingMap parent = new ParsingMap();
        NodeMap parentStep = nodeMap(
                MapConfigurations.MapType.STEP_MAP,
                "parentValue",
                "parent"
        );
        NodeMap callerPassed = nodeMap(
                MapConfigurations.MapType.PASSED_MAP,
                "callerOnly",
                "not inherited"
        );
        parent.addMaps(parentStep, callerPassed);

        NodeMap externalPassed = nodeMap(
                MapConfigurations.MapType.PASSED_MAP,
                "value",
                "external"
        );
        NodeMap storedPassed = nodeMap(
                MapConfigurations.MapType.PASSED_MAP,
                "value",
                "stored"
        );
        NodeMap examples = nodeMap(
                MapConfigurations.MapType.EXAMPLE_MAP,
                "value",
                "example"
        );

        ParsingMap result = ScenarioStepData.buildResolutionParsingMap(
                parent,
                externalPassed,
                storedPassed,
                examples
        );

        assertEquals("external", result.resolveWholeText("<value>"));
        assertEquals("parent", result.resolveWholeText("<parentValue>"));
        assertEquals("<callerOnly>", result.resolveWholeText("<callerOnly>"));
    }

    @Test
    void resolutionMapFallsBackFromStoredPassedToExamples() {
        NodeMap storedPassed = nodeMap(
                MapConfigurations.MapType.PASSED_MAP,
                "value",
                "stored"
        );
        NodeMap examples = nodeMap(
                MapConfigurations.MapType.EXAMPLE_MAP,
                "value",
                "example"
        );

        ParsingMap storedResult = ScenarioStepData.buildResolutionParsingMap(
                new ParsingMap(),
                null,
                storedPassed,
                examples
        );
        ParsingMap exampleResult = ScenarioStepData.buildResolutionParsingMap(
                new ParsingMap(),
                null,
                null,
                examples
        );

        assertEquals("stored", storedResult.resolveWholeText("<value>"));
        assertEquals("example", exampleResult.resolveWholeText("<value>"));
    }

    @Test
    void copiedNodeMapsAreIndependentFromGetterArguments() {
        NodeMap externalPassed = nodeMap(
                MapConfigurations.MapType.PASSED_MAP,
                "value",
                "before"
        );

        ParsingMap result = ScenarioStepData.buildResolutionParsingMap(
                new ParsingMap(),
                externalPassed,
                null,
                null
        );
        externalPassed.put("value", "after");

        assertEquals("before", result.resolveWholeText("<value>"));
        assertNull(result.getNodeMaps(MapConfigurations.MapType.EXAMPLE_MAP)
                .stream()
                .findFirst()
                .orElse(null));
    }

    private static NodeMap nodeMap(
            MapConfigurations.MapType mapType,
            String key,
            String value
    ) {
        NodeMap nodeMap = new NodeMap(mapType);
        nodeMap.put(key, value);
        return nodeMap;
    }
}
