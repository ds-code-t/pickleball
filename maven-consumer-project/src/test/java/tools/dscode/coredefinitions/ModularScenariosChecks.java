package tools.dscode.coredefinitions;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ModularScenariosChecks {

    @Test
    void inlineTagSelectorIsAddedToEveryInvocationRow() {
        DataTable table = DataTable.create(List.of(
                List.of("Run Tags", "customerName"),
                List.of("@existing", "Ava"),
                List.of("", "Ben")
        ));

        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps("%save_customer", table);

        assertEquals(
                List.of("%save_customer @existing", "%save_customer"),
                maps.stream().map(map -> map.get("Run Tags")).toList()
        );
    }

    @Test
    void labelledFeatureAndScenarioUseExactFilters() {
        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps(
                        "FEATURE: Save customer "
                                + "SCENARIO: component (default)",
                        null
                );

        assertEquals(1, maps.size());
        assertEquals(
                "^\\Qcomponent (default)\\E$",
                maps.getFirst().get("pkb_name")
        );
        assertEquals(
                "Save customer",
                maps.getFirst().get("pkb_featurename")
        );
    }

    @Test
    void labelledNamesPreservePeriodsAndInlineStartOverridesTheTable() {
        DataTable table = DataTable.create(List.of(
                List.of("Step_Marker"),
                List.of("table marker")
        ));

        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps(
                        "SCENARIO: Save customer v2.1 "
                                + "FEATURE: Reusable.flows "
                                + "START: inline marker",
                        table
                );

        assertEquals(
                "^\\QSave customer v2.1\\E$",
                maps.getFirst().get("pkb_name")
        );
        assertEquals(
                "Reusable.flows",
                maps.getFirst().get("pkb_featurename")
        );
        assertEquals(
                "inline marker",
                maps.getFirst().get("Step_Marker")
        );
    }

    @Test
    void labelledScenarioPreservesTableFeatureFilter() {
        DataTable table = DataTable.create(List.of(
                List.of("pkb_featurename", "customerName"),
                List.of("Reusable customer flows", "Ava")
        ));

        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps(
                        "SCENARIO: Save customer component",
                        table
                );

        assertEquals(
                "Reusable customer flows",
                maps.getFirst().get("pkb_featurename")
        );
        assertEquals(
                "^\\QSave customer component\\E$",
                maps.getFirst().get("pkb_name")
        );
    }

    @Test
    void featureOnlyInlineSelectorIsSupported() {
        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps(
                        "FEATURE: Reusable customer flows",
                        null
                );

        assertEquals(
                "Reusable customer flows",
                maps.getFirst().get("pkb_featurename")
        );
        assertNull(maps.getFirst().get("pkb_name"));
    }

    @Test
    void inlineStartMarkerIsStoredWithTheInvocationRow() {
        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps(
                        "SCENARIO: Save customer component "
                                + "START: submit customer",
                        null
                );

        assertEquals(
                "^\\QSave customer component\\E$",
                maps.getFirst().get("pkb_name")
        );
        assertEquals(
                "submit customer",
                maps.getFirst().get("Step_Marker")
        );
    }

    @Test
    void inlineTagExpressionCanBeCombinedWithAStartMarker() {
        DataTable table = DataTable.create(List.of(
                List.of("customerName"),
                List.of("Ava")
        ));

        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps(
                        "%save_customer START: submit customer",
                        table
                );

        assertEquals("%save_customer", maps.getFirst().get("Run Tags"));
        assertEquals(
                "submit customer",
                maps.getFirst().get("Step_Marker")
        );
    }

    @Test
    void tableStepMarkerIsPreservedWithoutInlineArguments() {
        DataTable table = DataTable.create(List.of(
                List.of("pkb_name", "Step_Marker"),
                List.of("^Save customer$", "submit customer")
        ));

        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps(null, table);

        assertEquals(
                "submit customer",
                maps.getFirst().get("Step_Marker")
        );
    }

    @Test
    void unlabelledNameSelectionIsRejected() {
        IllegalArgumentException scenarioName = assertThrows(
                IllegalArgumentException.class,
                () -> ModularScenarios.buildRunScenarioMaps(
                        "Save customer component",
                        null
                )
        );
        IllegalArgumentException qualifiedName = assertThrows(
                IllegalArgumentException.class,
                () -> ModularScenarios.buildRunScenarioMaps(
                        "Reusable customer flows.Save customer component",
                        null
                )
        );

        assertTrue(scenarioName.getMessage().contains("SCENARIO:"));
        assertTrue(qualifiedName.getMessage().contains("FEATURE:"));
    }

    @Test
    void labelledInlineArgumentsRequireValuesAndCannotRepeat() {
        IllegalArgumentException blankValue = assertThrows(
                IllegalArgumentException.class,
                () -> ModularScenarios.buildRunScenarioMaps(
                        "FEATURE: SCENARIO: Save customer component",
                        null
                )
        );
        IllegalArgumentException duplicateValue = assertThrows(
                IllegalArgumentException.class,
                () -> ModularScenarios.buildRunScenarioMaps(
                        "SCENARIO: First SCENARIO: Second",
                        null
                )
        );

        assertTrue(blankValue.getMessage().contains("FEATURE: requires"));
        assertTrue(duplicateValue.getMessage().contains("only be supplied once"));
    }

    @Test
    void singularSelectionRejectsMultipleOrderedAndLimitedMatches() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ModularScenarios.validateMatchCount(
                        List.of("First match", "Second match"),
                        false,
                        "RUN SCENARIO",
                        "component scenario"
                )
        );

        assertTrue(exception.getMessage().contains("matched 2 component scenarios"));
        assertTrue(exception.getMessage().contains("after ordering and limit"));
        assertTrue(exception.getMessage().contains("Use RUN SCENARIOS"));
    }

    @Test
    void pluralSelectionAllowsMultipleMatches() {
        assertDoesNotThrow(
                () -> ModularScenarios.validateMatchCount(
                        List.of("First match", "Second match"),
                        true,
                        "SERVICE CALL",
                        "service-call scenario"
                )
        );
    }

    @Test
    void runScenarioPatternCapturesThePluralFlagAndInlineArguments()
            throws ReflectiveOperationException {
        Method method = ModularScenarios.class.getDeclaredMethod(
                "runScenarios",
                String.class,
                String.class,
                DataTable.class
        );
        Pattern pattern = Pattern.compile(method.getAnnotation(Given.class).value());

        Matcher singular = pattern.matcher(
                "RUN SCENARIO: SCENARIO: Save customer component"
        );
        assertTrue(singular.matches());
        assertNull(singular.group(1));
        assertEquals(
                " SCENARIO: Save customer component",
                singular.group(2)
        );

        Matcher plural = pattern.matcher("RUN SCENARIOS: %save_customer");
        assertTrue(plural.matches());
        assertEquals("S", plural.group(1));
        assertEquals(" %save_customer", plural.group(2));
    }

    @Test
    void serviceCallPatternCapturesThePluralFlagAndInlineArguments()
            throws ReflectiveOperationException {
        Method method = ServiceCallSteps.class.getDeclaredMethod(
                "serviceCalls",
                String.class,
                String.class,
                String.class,
                DataTable.class
        );
        Pattern pattern = Pattern.compile(method.getAnnotation(Given.class).value());

        Matcher singular = pattern.matcher(
                "\"health\" SERVICE CALL: SCENARIO: HealthCall"
        );
        assertTrue(singular.matches());
        assertEquals("health", singular.group(1));
        assertNull(singular.group(2));
        assertEquals(" SCENARIO: HealthCall", singular.group(3));

        Matcher plural = pattern.matcher("SERVICE CALLS: %health");
        assertTrue(plural.matches());
        assertNull(plural.group(1));
        assertEquals("S", plural.group(2));
        assertEquals(" %health", plural.group(3));
    }


    @Test
    void explicitDataPathOverridesTableFeaturePath() {
        Map<String, String> options = new java.util.HashMap<>();
        options.put("pkb_features", "table/path");
        options.put("cucumber.features", "other/path");

        ModularScenarios.applyFeaturesPathOverride(
                options,
                "configured/data/path"
        );

        assertNull(options.get("pkb_features"));
        assertEquals(
                "configured/data/path",
                options.get("cucumber.features")
        );
    }

    @Test
    void inlineCallAcceptsAnOptionalDataTable()
            throws ReflectiveOperationException {
        Method method = ServiceCallSteps.class.getDeclaredMethod(
                "inlineCall",
                String.class,
                DataTable.class
        );

        assertEquals(Object.class, method.getReturnType());
    }

    @Test
    void scenarioDataLookupReturnsNullWhenNoMarkerIsSupplied() {
        assertNull(ModularScenarios.getScenarioStepData(
                "SCENARIO: Save customer component",
                null
        ));
    }
    @Test
    void dataAddressesAreLeftPaddedAndPreservePositions() {
        ModularScenarios.DataAddress marker =
                ModularScenarios.parseDataAddress("payload");
        ModularScenarios.DataAddress scenario =
                ModularScenarios.parseDataAddress("Customer data.payload");
        ModularScenarios.DataAddress feature =
                ModularScenarios.parseDataAddress(
                        "Data records.Customer data.payload"
                );

        assertEquals("", marker.featureName());
        assertEquals("", marker.scenarioName());
        assertEquals("payload", marker.stepMarker());

        assertEquals("", scenario.featureName());
        assertEquals("Customer data", scenario.scenarioName());
        assertEquals("payload", scenario.stepMarker());

        assertEquals("Data records", feature.featureName());
        assertEquals("Customer data", feature.scenarioName());
        assertEquals("payload", feature.stepMarker());
    }

    @Test
    void invalidOrBlankDataAddressesAreHandledDescriptively() {
        assertNull(ModularScenarios.parseDataAddress(null));
        assertNull(ModularScenarios.parseDataAddress(""));
        assertNull(ModularScenarios.parseDataAddress("Scenario."));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ModularScenarios.parseDataAddress("a.b.c.d")
        );
        assertTrue(exception.getMessage().contains(
                "cannot contain period characters"
        ));
    }


}
