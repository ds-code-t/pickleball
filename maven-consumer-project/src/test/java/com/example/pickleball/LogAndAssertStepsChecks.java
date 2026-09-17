package com.example.pickleball;

import io.cucumber.core.runner.CurrentScenarioState;
import org.junit.jupiter.api.Test;
import tools.dscode.control.api.ControlCallResult;
import tools.dscode.control.api.ControlCallStatus;
import tools.dscode.control.api.DynamicControl;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LogAndAssertStepsChecks {

    @Test
    void infoDoesNotFailTheScenario() {
        CurrentScenarioState state = requireScenario();
        ControlCallResult<Object> result = DynamicControl.executeStep("INFO: starting checkout");
        assertTrue(result.successful(), () -> String.valueOf(result.error()));
        assertFalse(state.isScenarioFailed());
    }

    @Test
    void errorHardFailsWithoutFailingTheParentScenario() {
        CurrentScenarioState state = requireScenario();
        ControlCallResult<Object> result = DynamicControl.executeStep("ERROR: missing session");
        assertEquals(ControlCallStatus.FAILED, result.status());
        assertNotNull(result.error());
        assertTrue(result.error().message().contains("missing session"));
        assertFalse(state.isScenarioFailed());
    }

    @Test
    void failTokenHardFailsWithoutFailingTheParentScenario() {
        CurrentScenarioState state = requireScenario();
        ControlCallResult<Object> result = DynamicControl.executeStep("FAIL: ready state never reached");
        assertEquals(ControlCallStatus.FAILED, result.status());
        assertNotNull(result.error());
        assertTrue(result.error().message().contains("ready state never reached"));
        assertFalse(state.isScenarioFailed());
    }

    @Test
    void hardAssertPassesExpressionAndPhraseForms() {
        assertSuccessful("ASSERT: 1 < 2");
        assertSuccessful("ASSERT: \"A\" equals \"A\"");
        assertSuccessful("ASSERT: 'A' equals 'a'");
        assertSuccessful("ASSERT: 1 < 2 || false");
        assertSuccessful("ASSERT: 1 < 2 | \"A\" equals \"A\"");
    }

    @Test
    void hardAssertFailFastLogsTheFirstClauseOnly() {
        CurrentScenarioState state = requireScenario();
        ControlCallResult<Object> result = DynamicControl.executeStep("ASSERT: 2 < 1 | 3 < 1");
        assertEquals(ControlCallStatus.FAILED, result.status());
        assertNotNull(result.error());
        String detail = result.error().message() + result.error().stackTrace();
        assertTrue(detail.contains("ASSERT failed: 2 < 1"), detail);
        assertFalse(detail.contains("ASSERT failed: 3 < 1"), detail);
        assertFalse(state.isScenarioFailed());
    }

    @Test
    void softAssertSuccessDoesNotFailTheScenario() {
        CurrentScenarioState state = requireScenario();
        ControlCallResult<Object> result = DynamicControl.executeStep("SOFT ASSERT: 1 < 2 | \"A\" equals \"A\"");
        assertTrue(result.successful(), () -> String.valueOf(result.error()));
        assertFalse(state.isScenarioFailed());
    }

    @Test
    void softAssertEvaluatesEveryClauseLogsEachFailureAndAllowsLaterSteps() {
        CurrentScenarioState state = requireScenario();
        ControlCallResult<Object> result =
                DynamicControl.executeStep("SOFT ASSERT: 1 == 2 | \"A\" equals \"B\"");
        assertEquals(ControlCallStatus.FAILED, result.status());
        assertNotNull(result.error());
        String detail = result.error().message() + result.error().stackTrace();
        assertTrue(detail.contains("SOFT ASSERT failed: 1 == 2"), detail);
        assertTrue(detail.contains("SOFT ASSERT failed: \"A\" equals \"B\""), detail);
        assertFalse(state.isScenarioFailed());

        ControlCallResult<Object> save = DynamicControl.executeStep(
                ", save \"continued\" as \"afterSoftFail\""
        );
        assertTrue(save.successful(), () -> String.valueOf(save.error()));
        ControlCallResult<Object> ensure = DynamicControl.executeStep(
                ", ensure \"<afterSoftFail>\" equals \"continued\""
        );
        assertTrue(ensure.successful(), () -> String.valueOf(ensure.error()));
        assertFalse(state.isScenarioFailed());
    }

    private static void assertSuccessful(String stepText) {
        CurrentScenarioState state = requireScenario();
        ControlCallResult<Object> result = DynamicControl.executeStep(stepText);
        assertTrue(result.successful(), () -> stepText + " -> " + result.error());
        assertFalse(state.isScenarioFailed());
    }

    private static CurrentScenarioState requireScenario() {
        CurrentScenarioState state = getCurrentScenarioState();
        assertNotNull(state);
        assertFalse(state.isScenarioFailed());
        return state;
    }
}
