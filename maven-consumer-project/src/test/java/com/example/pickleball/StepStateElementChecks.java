package com.example.pickleball;

import io.cucumber.core.runner.CurrentScenarioState;
import org.junit.jupiter.api.Test;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.LogForwarder;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.control.api.ControlCallResult;
import tools.dscode.control.api.ControlCallStatus;
import tools.dscode.control.api.DynamicControl;

import java.util.HashSet;
import java.util.Set;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;

public class StepStateElementChecks {

    private static final String RESERVED_STEP_COUNT =
            "'Step Count' is a reserved element name. Names starting with 'Step' are reserved for step-state elements; supported: Step Repetition, Step Duration.";

    @Test
    void enumConstantNamesAreOrdinaryHtmlElements() {
        assertHtml("Url");
        assertHtml("Html Iframe");
        assertHtml("Value Type");
        assertHtml("Stepwise");
        assertFalse(ElementType.fromString("Url").contains(ElementType.URL));
        assertFalse(ElementType.fromString("Html Iframe").contains(ElementType.HTML_IFRAME));
        assertFalse(ElementType.fromString("Value Type").contains(ElementType.VALUE_TYPE));
        assertNull(ElementType.reservationWarning("Url"));
        assertNull(ElementType.reservationWarning("Close Button"));
        assertNull(ElementType.reservationWarning("Loading"));
        assertNull(ElementType.reservationWarning("Button"));
    }

    @Test
    void stepRepetitionAndDurationResolveAndOtherStepNamesFail() {
        assertTrue(ElementType.fromString("Step Repetition").contains(ElementType.STEP_REPETITION));
        assertTrue(ElementType.fromString("Step Repetitions").contains(ElementType.STEP_REPETITION));
        assertTrue(ElementType.fromString("Step Duration").contains(ElementType.STEP_DURATION));
        assertTrue(ElementType.fromString("Step Durations").contains(ElementType.STEP_DURATION));
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ElementType.fromString("Step Count")
        );
        assertEquals(RESERVED_STEP_COUNT, thrown.getMessage());
        assertEquals(RESERVED_STEP_COUNT, ElementType.reservationWarning("Step Count"));
        assertThrows(IllegalArgumentException.class, () -> ElementType.fromString("Step"));
        assertThrows(IllegalArgumentException.class, () -> ElementType.fromString("Step Repetition Count"));
    }

    @Test
    void stepCountStepFailsWithTheReservedNameMessage() {
        CurrentScenarioState state = getCurrentScenarioState();
        ControlCallResult<Object> result = DynamicControl.executeStep(", save the Step Count as \"stepCount\"");
        String detail = detail(result);
        assertEquals(ControlCallStatus.FAILED, result.status(), detail);
        assertTrue(detail.contains(RESERVED_STEP_COUNT), detail);
        assertFalse(detail.contains("NullPointerException"), detail);
        if (state != null) {
            assertFalse(state.isScenarioFailed(), detail);
        }
    }

    @Test
    void reservedCategoryLogsAWarningAndOrdinaryHtmlDoesNot() {
        ExecutionDictionary dictionary = getExecutionDictionary();
        try {
            assertWarning(() -> dictionary.category("Step Foo"), "Step Foo", "reserved element name");
            assertWarning(() -> dictionary.category("Data Table"), "Data Table", "Data Elements");
            assertWarning(
                    () -> dictionary.category("Probe Panel").children("Data Table"),
                    "Data Table",
                    "Data Elements"
            );
            assertNoReservedWarning(() -> dictionary.category("Close Button"));
            assertNoReservedWarning(() -> dictionary.category("Loading"));
            assertNoReservedWarning(() -> dictionary.category("Button"));
            assertNoReservedWarning(() -> dictionary.categories("Url"));
        } finally {
            dictionary.resetCategory("Step Foo");
            dictionary.resetCategory("Data Table");
            dictionary.resetCategory("Probe Panel");
        }
    }

    private static void assertHtml(String name) {
        Set<ElementType> types = ElementType.fromString(name);
        assertTrue(types.contains(ElementType.HTML_TYPE), name + " -> " + types);
        assertFalse(types.contains(ElementType.BROWSER_TYPE), name);
        assertFalse(types.contains(ElementType.DATA_TYPE), name);
        assertFalse(types.contains(ElementType.STEP_TYPE), name);
        assertFalse(types.contains(ElementType.VALUE_TYPE), name);
    }

    private static void assertWarning(Runnable register, String name, String expected) {
        Set<String> before = entryIds();
        register.run();
        String log = newTexts(before);
        String needle = "Custom element category '" + name + "' will not be used.";
        assertTrue(log.contains(needle), log);
        assertTrue(log.contains(expected), log);
    }

    private static void assertNoReservedWarning(Runnable register) {
        Set<String> before = entryIds();
        register.run();
        String log = newTexts(before);
        assertFalse(log.contains("will not be used"), log);
    }

    private static Set<String> entryIds() {
        Set<String> ids = new HashSet<>();
        collectIds(LogForwarder.getDefaultEntry(), ids);
        return ids;
    }

    private static void collectIds(Entry entry, Set<String> ids) {
        if (entry == null || !ids.add(entry.id)) {
            return;
        }
        for (Entry child : entry.children) {
            collectIds(child, ids);
        }
    }

    private static String newTexts(Set<String> before) {
        StringBuilder joined = new StringBuilder();
        appendNew(LogForwarder.getDefaultEntry(), before, joined, new HashSet<>());
        return joined.toString();
    }

    private static void appendNew(Entry entry, Set<String> before, StringBuilder joined, Set<String> seen) {
        if (entry == null || !seen.add(entry.id)) {
            return;
        }
        if (!before.contains(entry.id) && entry.text != null) {
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(entry.text);
        }
        for (Entry child : entry.children) {
            appendNew(child, before, joined, seen);
        }
    }

    private static String detail(ControlCallResult<Object> result) {
        if (result.error() == null) {
            return String.valueOf(result.status());
        }
        return result.error().message() + "\n" + result.error().stackTrace();
    }
}
