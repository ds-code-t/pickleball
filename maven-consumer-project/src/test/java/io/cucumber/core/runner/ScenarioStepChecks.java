package io.cucumber.core.runner;

import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.ParsingMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
