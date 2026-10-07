package io.cucumber.core.runner;

import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Proxy;

import io.cucumber.core.gherkin.Pickle;

import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void trimsLeadingDashesAndWhitespaceFromMarkerText() {
        assertEquals(
                "payload",
                ScenarioStep.normalizeStepMarkerText("payload")
        );
        assertEquals(
                "payload",
                ScenarioStep.normalizeStepMarkerText("-payload")
        );
        assertEquals(
                "payload",
                ScenarioStep.normalizeStepMarkerText("--- --payload")
        );
        assertEquals(
                "pay-load 2",
                ScenarioStep.normalizeStepMarkerText(
                        "--- -- pay-load 2"
                )
        );
        assertEquals(
                "payload",
                ScenarioStep.resolveMarkerText(
                        "--- --payload",
                        new ParsingMap()
                )
        );
        assertTrue(
                ScenarioStep.matchesStepMarker(
                        ScenarioStep.resolveMarkerText(
                                "--- --payload",
                                new ParsingMap()
                        ),
                        "payload"
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
    void nearestMarkerSearchPrefersBelowThenFallsBackAbove() {
        List<Integer> lines = List.of(2, 7, 12, 18);

        assertEquals(
                12,
                ScenarioStep.findNearestByLine(
                        lines,
                        10,
                        Integer::intValue,
                        line -> true
                )
        );
        assertEquals(
                7,
                ScenarioStep.findNearestByLine(
                        lines,
                        10,
                        Integer::intValue,
                        line -> line < 10
                )
        );
        assertEquals(
                18,
                ScenarioStep.findNearestByLine(
                        lines,
                        10,
                        Integer::intValue,
                        line -> line == 18
                )
        );
        assertNull(
                ScenarioStep.findNearestByLine(
                        lines,
                        10,
                        Integer::intValue,
                        line -> false
                )
        );
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

    @Test
    void backgroundLookupFailureIsRaisedInsteadOfKeepingBackgrounds() {
        URI uri = URI.create("file:///area-b-missing-background.feature");
        Pickle pickle = (Pickle) Proxy.newProxyInstance(
                Pickle.class.getClassLoader(),
                new Class<?>[]{Pickle.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUri" -> uri;
                    case "getName" -> "area-b-missing";
                    case "toString" -> "area-b-missing-pickle";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultProxyValue(method.getReturnType());
                }
        );
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> CalledFeatureBackground.omitBackgroundSteps(pickle, new ArrayList<>())
        );
        assertTrue(
                thrown.getMessage().contains("Could not determine background steps"),
                thrown.getMessage()
        );
        assertTrue(thrown.getMessage().contains(uri.toString()), thrown.getMessage());
    }

    private static Object defaultProxyValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
