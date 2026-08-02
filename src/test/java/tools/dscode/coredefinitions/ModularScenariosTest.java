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

class ModularScenariosTest {

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
    void inlineScenarioNameUsesAnExactCucumberNameFilter() {
        List<Map<String, String>> maps =
                ModularScenarios.buildRunScenarioMaps(
                        "Save customer.component (default)",
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
    void inlineScenarioOnlyPreservesTableFeatureFilter() {
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
    void qualifiedInlineSelectorRequiresBothNames() {
        IllegalArgumentException missingFeature = assertThrows(
                IllegalArgumentException.class,
                () -> ModularScenarios.buildRunScenarioMaps(
                        ".Save customer component",
                        null
                )
        );
        IllegalArgumentException missingScenario = assertThrows(
                IllegalArgumentException.class,
                () -> ModularScenarios.buildRunScenarioMaps(
                        "Reusable customer flows.",
                        null
                )
        );

        assertTrue(missingFeature.getMessage().contains("both names present"));
        assertTrue(missingScenario.getMessage().contains("both names present"));
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

        Matcher singular = pattern.matcher("RUN SCENARIO: Save customer component");
        assertTrue(singular.matches());
        assertNull(singular.group(1));
        assertEquals(" Save customer component", singular.group(2));

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
                "\"health\" SERVICE CALL: HealthCall"
        );
        assertTrue(singular.matches());
        assertEquals("health", singular.group(1));
        assertNull(singular.group(2));
        assertEquals(" HealthCall", singular.group(3));

        Matcher plural = pattern.matcher("SERVICE CALLS: %health");
        assertTrue(plural.matches());
        assertNull(plural.group(1));
        assertEquals("S", plural.group(2));
        assertEquals(" %health", plural.group(3));
    }
}
