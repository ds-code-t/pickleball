package io.cucumber.core.runner;

import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
public class ScenarioStepChecks {
    @Test
    void missingStartMarkerCanBeResolvedWithAParsingMap() {
        assertEquals("", ScenarioStep.resolveMarkerText(null, new ParsingMap()));
        assertEquals("", ScenarioStep.resolveMarkerText(" ", new ParsingMap()));
    }
    @Test
    void matchesDefaultAndCustomStartMarkersCaseInsensitively() {
        assertTrue(ScenarioStep.matchesStepMarker("startstep", "startstep"));
        assertTrue(
                ScenarioStep.matchesStepMarker(
                        "Component Start",
                        "component start"
                )
        );
    }
    @Test
    void requiresTheCompleteMarkerText() {
        assertFalse(
                ScenarioStep.matchesStepMarker(
                        "startstep reusable section",
                        "startstep"
                )
        );
        assertFalse(
                ScenarioStep.matchesStepMarker(
                        "startstepExtra",
                        "startstep"
                )
        );
    }
    @Test
    void customMarkerDoesNotAlsoMatchTheDefaultMarker() {
        assertFalse(
                ScenarioStep.matchesStepMarker(
                        "startstep",
                        "component start"
                )
        );
        assertFalse(
                ScenarioStep.matchesStepMarker(
                        "component start",
                        "startstep"
                )
        );
    }

    @Test
    void emptyAndDashOnlyMarkersAreUnnamed() {
        assertTrue(ScenarioStep.isUnnamedStepMarker(null));
        assertTrue(ScenarioStep.isUnnamedStepMarker(""));
        assertTrue(ScenarioStep.isUnnamedStepMarker(" "));
        assertTrue(ScenarioStep.isUnnamedStepMarker("-"));
        assertTrue(ScenarioStep.isUnnamedStepMarker("---"));
        assertTrue(ScenarioStep.isUnnamedStepMarker("- -"));

        assertFalse(ScenarioStep.isUnnamedStepMarker("payload"));
        assertFalse(ScenarioStep.isUnnamedStepMarker("--payload"));
    }

    @Test
    void unresolvedMarkerKeysAreResolvedWhenLookedUp() {
        ParsingMap parsingMap = new ParsingMap();
        NodeMap passedMap =
                new NodeMap(MapConfigurations.MapType.PASSED_MAP);
        passedMap.put("firstMarker", "payload");
        passedMap.put("secondMarker", "payload");
        parsingMap.addMaps(passedMap);

        Map<String, String> markers = new LinkedHashMap<>();
        markers.put("<firstMarker>", "first");
        markers.put("<secondMarker>", "second");

        assertEquals(
                "second",
                ScenarioStep.findStepMarker(
                        markers,
                        "PAYLOAD",
                        parsingMap
                )
        );
        assertEquals(
                "second",
                ScenarioStep.findStepMarker(
                        markers,
                        "<firstMarker>",
                        parsingMap
                )
        );
        assertNull(
                ScenarioStep.findStepMarker(
                        markers,
                        "",
                        parsingMap
                )
        );
    }
}
