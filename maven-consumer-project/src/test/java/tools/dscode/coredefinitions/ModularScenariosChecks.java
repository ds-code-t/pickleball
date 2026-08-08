package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.custommappings.ValConverter;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void scenarioOnlyPathSelectsScenarioNameExactly() {
        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps(
                        "Save customer component",
                        null
                );

        assertEquals(
                "^\\QSave customer component\\E$",
                maps.getFirst().get("pkb_name")
        );
        assertNull(maps.getFirst().get("pkb_featurename"));
        assertNull(maps.getFirst().get("Step_Marker"));
    }

    @Test
    void featureAndScenarioPathUseExactFilters() {
        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps(
                        "Save customer.component (default)",
                        null
                );

        assertEquals("Save customer", maps.getFirst().get("pkb_featurename"));
        assertEquals(
                "^\\Qcomponent (default)\\E$",
                maps.getFirst().get("pkb_name")
        );
        assertNull(maps.getFirst().get("Step_Marker"));
    }

    @Test
    void featureScenarioAndMarkerPathSetAllSelectors() {
        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps(
                        "Reusable customer flows.Save customer component.submit customer",
                        null
                );

        assertEquals(
                "Reusable customer flows",
                maps.getFirst().get("pkb_featurename")
        );
        assertEquals(
                "^\\QSave customer component\\E$",
                maps.getFirst().get("pkb_name")
        );
        assertEquals("submit customer", maps.getFirst().get("Step_Marker"));
    }

    @Test
    void escapedPathNamesPreservePeriodsAndBackslashesAndInlineMarkerOverridesTable() {
        DataTable table = DataTable.create(List.of(
                List.of("Step_Marker"),
                List.of("table marker")
        ));

        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps(
                        "Reusable\\.flows.Save customer v2\\.1.inline\\\\marker",
                        table
                );

        assertEquals("Reusable.flows", maps.getFirst().get("pkb_featurename"));
        assertEquals(
                "^\\QSave customer v2.1\\E$",
                maps.getFirst().get("pkb_name")
        );
        assertEquals("inline\\marker", maps.getFirst().get("Step_Marker"));
    }

    @Test
    void scenarioPathPreservesTableFeatureFilter() {
        DataTable table = DataTable.create(List.of(
                List.of("pkb_featurename", "customerName"),
                List.of("Reusable customer flows", "Ava")
        ));

        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps(
                        "Save customer component",
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
    void tableStepMarkerIsPreservedWithoutInlineMarker() {
        DataTable table = DataTable.create(List.of(
                List.of("pkb_name", "Step_Marker"),
                List.of("^Save customer$", "submit customer")
        ));

        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps(null, table);

        assertEquals("submit customer", maps.getFirst().get("Step_Marker"));
    }

    @Test
    void blankAndOverlongSelectorPathsAreRejected() {
        for (String selector : List.of(
                ".Scenario",
                "Feature.",
                "Feature..Scenario",
                "Feature.Scenario.Marker.Extra"
        )) {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> ModularScenarios.buildRunScenarioMaps(selector, null)
            );
            assertTrue(exception.getMessage().contains("feature.scenario"));
        }
    }

    @Test
    void escapedPathTokenizerOnlySplitsUnescapedPeriods() {
        assertEquals(
                List.of("Feature.v2", "Scenario.1", "marker.a"),
                ModularScenarios.splitEscapedPath(
                        "Feature\\.v2.Scenario\\.1.marker\\.a"
                )
        );
        assertEquals(
                List.of("Feature\\name", "Scenario"),
                ModularScenarios.splitEscapedPath(
                        "Feature\\\\name.Scenario"
                )
        );
    }

    @Test
    void singularSelectionRejectsMultipleMatches() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ModularScenarios.validateMatchCount(
                        List.of("First match", "Second match"),
                        false,
                        "RUN SCENARIO",
                        "scenario"
                )
        );

        assertTrue(exception.getMessage().contains("matched 2 scenarios"));
        assertTrue(exception.getMessage().contains("Use RUN SCENARIOS"));
    }

    @Test
    void pluralSelectionAllowsMultipleMatches() {
        assertDoesNotThrow(
                () -> ModularScenarios.validateMatchCount(
                        List.of("First match", "Second match"),
                        true,
                        "RUN SERVICE CALL",
                        "service-call scenario"
                )
        );
    }

    @Test
    void runScenarioPatternCapturesNewPathSyntax() throws ReflectiveOperationException {
        Method method = ModularScenarios.class.getDeclaredMethod(
                "runScenarios",
                String.class,
                String.class,
                String.class,
                String.class,
                DataTable.class
        );
        Pattern pattern = Pattern.compile(method.getAnnotation(Given.class).value());

        Matcher singular = pattern.matcher(
                "RUN SCENARIO: Reusable scenario selection.Selection fixture A"
        );
        assertTrue(singular.matches());
        assertNull(singular.group(1));
        assertEquals("SCENARIO", singular.group(2));
        assertNull(singular.group(3));
        assertEquals(
                " Reusable scenario selection.Selection fixture A",
                singular.group(4)
        );

        Matcher keyedCall = pattern.matcher(
                "RUN \"health\" SERVICE CALL: Reusable service call definitions.HealthCall"
        );
        assertTrue(keyedCall.matches());
        assertEquals("health", keyedCall.group(1));
        assertEquals("SERVICE CALL", keyedCall.group(2));
        assertNull(keyedCall.group(3));
        assertEquals(
                " Reusable service call definitions.HealthCall",
                keyedCall.group(4)
        );

        Matcher plural = pattern.matcher(
                "RUN COMPONENT SCENARIOS: %save_customer"
        );
        assertTrue(plural.matches());
        assertEquals("COMPONENT SCENARIO", plural.group(2));
        assertEquals("S", plural.group(3));
        assertEquals(" %save_customer", plural.group(4));
    }

    @Test
    void legacyServiceCallWrapperAcceptsNewSelectorPath() throws ReflectiveOperationException {
        Method method = ServiceCallSteps.class.getDeclaredMethod(
                "serviceCalls",
                String.class,
                String.class,
                String.class,
                DataTable.class
        );
        Pattern pattern = Pattern.compile(method.getAnnotation(Given.class).value());

        Matcher singular = pattern.matcher(
                "\"health\" SERVICE CALL: Reusable service call definitions.HealthCall"
        );
        assertTrue(singular.matches());
        assertEquals("health", singular.group(1));
        assertNull(singular.group(2));
        assertEquals(
                " Reusable service call definitions.HealthCall",
                singular.group(3)
        );

        Matcher plural = pattern.matcher("SERVICE CALLS: %health");
        assertTrue(plural.matches());
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
    void convenienceMethodsAcceptOptionalDataTableAndReturnObject()
            throws ReflectiveOperationException {
        Method inlineCall = ServiceCallSteps.class.getDeclaredMethod(
                "inlineCall",
                String.class,
                DataTable.class
        );
        Method inlineScenario = ModularScenarios.class.getDeclaredMethod(
                "inlineScenario",
                String.class,
                DataTable.class
        );
        Method inlineComponent = ModularScenarios.class.getDeclaredMethod(
                "inlineComponent",
                String.class,
                DataTable.class
        );

        assertEquals(Object.class, inlineCall.getReturnType());
        assertEquals(Object.class, inlineScenario.getReturnType());
        assertEquals(Object.class, inlineComponent.getReturnType());
    }

    @Test
    void legacyServiceCallStepIsExplicitlyDeprecated() throws ReflectiveOperationException {
        Method method = ServiceCallSteps.class.getDeclaredMethod(
                "serviceCalls",
                String.class,
                String.class,
                String.class,
                DataTable.class
        );

        assertTrue(method.isAnnotationPresent(Deprecated.class));
    }

    @Test
    void scenarioReturnValueDistinguishesMissingAndExplicitNull() {
        NodeMap missingReturn = new NodeMap(MapConfigurations.MapType.STEP_MAP);
        assertSame(
                missingReturn.getRoot(),
                ModularScenarios.scenarioReturnValue(missingReturn)
        );

        NodeMap explicitNull = new NodeMap(MapConfigurations.MapType.STEP_MAP);
        explicitNull.put("RETURN", null);
        assertNull(ModularScenarios.scenarioReturnValue(explicitNull));

        NodeMap explicitValue = new NodeMap(MapConfigurations.MapType.STEP_MAP);
        explicitValue.put("RETURN", "selected");
        assertEquals(
                "selected",
                ModularScenarios.scenarioReturnValue(explicitValue)
        );
    }

    @Test
    void explicitNullMarkersProduceNullNodesWithoutChangingEmbeddedText() {
        Object direct = ValConverter.convertSpecialValues("<^~NULL~^>");
        assertTrue(direct instanceof JsonNode);
        assertTrue(((JsonNode) direct).isNull());
        assertTrue(MappingSteps.hasNonBlankValue(direct));

        JsonNode structured = ValConverter.convertSpecialValuesToTree(Map.of(
                "A", "^~NULL~^",
                "B", "prefix ^~NULL~^ suffix"
        ));
        assertTrue(structured.path("A").isNull());
        assertEquals(
                "prefix ^~NULL~^ suffix",
                structured.path("B").asText()
        );
    }

    @Test
    void scenarioDataLookupReturnsNullWhenNoMarkerIsSupplied() {
        assertNull(ModularScenarios.getScenarioStepData(
                "Save customer component",
                null
        ));
    }

    @Test
    void dataAddressesAreRightAlignedByComponentCount() {
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
    void dataAddressMapsKeepRightAlignedFieldsAndPreserveTableFilters() {
        DataTable options = DataTable.create(List.of(
                List.of("pkb_features", "pkb_name", "Step_Marker"),
                List.of("src/test/resources/data", "^Customer record$", "table marker")
        ));

        List<Map<String, String>> markerOnly =
                ModularScenarios.buildDataScenarioMaps(
                        ModularScenarios.parseDataAddress("payload"),
                        options
                );

        assertEquals(
                "src/test/resources/data",
                markerOnly.getFirst().get("pkb_features")
        );
        assertEquals("^Customer record$", markerOnly.getFirst().get("pkb_name"));
        assertEquals("payload", markerOnly.getFirst().get("Step_Marker"));

        List<Map<String, String>> scenarioAndMarker =
                ModularScenarios.buildDataScenarioMaps(
                        ModularScenarios.parseDataAddress("Customer record.payload"),
                        null
                );

        assertNull(scenarioAndMarker.getFirst().get("pkb_featurename"));
        assertEquals(
                "^\\QCustomer record\\E$",
                scenarioAndMarker.getFirst().get("pkb_name")
        );
        assertEquals("payload", scenarioAndMarker.getFirst().get("Step_Marker"));

        List<Map<String, String>> fullAddress =
                ModularScenarios.buildDataScenarioMaps(
                        ModularScenarios.parseDataAddress(
                                "Data\\.reference\\.records.Customer\\.record.payload\\.marker"
                        ),
                        options
                );

        assertEquals(
                "Data.reference.records",
                fullAddress.getFirst().get("pkb_featurename")
        );
        assertEquals(
                "^\\QCustomer.record\\E$",
                fullAddress.getFirst().get("pkb_name")
        );
        assertEquals("payload.marker", fullAddress.getFirst().get("Step_Marker"));
    }

    @Test
    void dataAddressesPreserveEscapedPeriodsAndBackslashes() {
        ModularScenarios.DataAddress address = ModularScenarios.parseDataAddress(
                "Data\\.records.Customer\\.record.payload\\.marker"
        );

        assertEquals("Data.records", address.featureName());
        assertEquals("Customer.record", address.scenarioName());
        assertEquals("payload.marker", address.stepMarker());

        ModularScenarios.DataAddress backslash = ModularScenarios.parseDataAddress(
                "Customer\\\\record.payload"
        );
        assertEquals("Customer\\record", backslash.scenarioName());
        assertEquals("payload", backslash.stepMarker());
    }

    @Test
    void invalidOrBlankDataAddressesAreHandledDescriptively() {
        assertNull(ModularScenarios.parseDataAddress(null));
        assertNull(ModularScenarios.parseDataAddress(""));
        assertNull(ModularScenarios.parseDataAddress("Scenario."));

        for (String address : List.of("Feature..marker", "a.b.c.d")) {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> ModularScenarios.parseDataAddress(address)
            );
            assertTrue(exception.getMessage().contains("feature.scenario.marker"));
        }
    }
}
