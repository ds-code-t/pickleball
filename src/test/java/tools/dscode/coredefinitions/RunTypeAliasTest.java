package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunTypeAliasTest {
    @Test
    void shortAliasesMapInStepTextAndTableCells() throws Exception {
        Method method = ModularScenarios.class.getDeclaredMethod(
                "runScenarios", String.class, String.class, String.class, io.cucumber.datatable.DataTable.class
        );
        Pattern pattern = Pattern.compile(method.getAnnotation(Given.class).value());
        assertTrue(pattern.matcher("RUN COMPONENT: Save").matches());
        assertGroup(pattern, "RUN COMPONENT: Save", "COMPONENT");
        assertGroup(pattern, "RUN COMPONENTS", "COMPONENTS");
        assertGroup(pattern, "RUN CALL: Health", "CALL");
        assertGroup(pattern, "RUN CALLS: Health", "CALLS");
        assertGroup(pattern, "RUN SERVICE: Health", "SERVICE");
        assertGroup(pattern, "RUN SERVICES", "SERVICES");
        assertGroup(pattern, "RUN COMPONENT SCENARIOS: %save", "COMPONENT SCENARIOS");
        assertGroup(pattern, "RUN SERVICE CALL: Health", "SERVICE CALL");
        assertFalse(pattern.matcher("run component: Save").matches());

        assertSelection("COMPONENT", "COMPONENT SCENARIO", false);
        assertSelection("components", "COMPONENT SCENARIO", true);
        assertSelection("CALL", "SERVICE CALL", false);
        assertSelection("CALLS", "SERVICE CALL", true);
        assertSelection("service", "SERVICE CALL", false);
        assertSelection("SERVICES", "SERVICE CALL", true);
        assertSelection("COMPONENT SCENARIO", "COMPONENT SCENARIO", false);
        assertSelection("SERVICE CALLS", "SERVICE CALL", true);
        assertSelection("SCENARIOS", "SCENARIO", true);

        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> ModularScenarios.rejectSingularMatches(List.of("A", "B"), "COMPONENT")
        );
        assertTrue(rejected.getMessage().startsWith("error:"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("fix:"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("COMPONENT SCENARIOS"), rejected.getMessage());
        ModularScenarios.rejectSingularMatches(List.of("A", "B"), "COMPONENTS");
        ModularScenarios.rejectSingularMatches(List.of("A", "B"), "SERVICE CALLS");
    }

    private static void assertGroup(Pattern pattern, String text, String type) {
        java.util.regex.Matcher matcher = pattern.matcher(text);
        assertTrue(matcher.matches(), text);
        assertEquals(type, matcher.group(2), text);
    }

    private static void assertSelection(String text, String stepText, boolean multiple) {
        ModularScenarios.RunSelection selection = ModularScenarios.runSelection(text);
        assertEquals(stepText, selection.runType().stepText(), text);
        assertEquals(multiple, selection.allowMultiple(), text);
    }
}
