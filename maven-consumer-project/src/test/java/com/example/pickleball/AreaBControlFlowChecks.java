package com.example.pickleball;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.StepData;
import io.cucumber.core.runner.StepExtension;
import org.junit.jupiter.api.Test;
import tools.dscode.common.exceptions.StepCreationException;
import tools.dscode.common.gherkinoperations.DynamicExecution;
import tools.dscode.control.api.ControlCallResult;
import tools.dscode.control.api.ControlCallStatus;
import tools.dscode.control.api.DynamicControl;

import java.time.Duration;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.setProperty;

public class AreaBControlFlowChecks {

    @Test
    void untilExhaustedByCountHardFails() {
        Throwable exhausted = runUntil(", until \"\" equals \"done\":", 0, Duration.ofHours(1));
        String detail = detail(exhausted);
        assertTrue(detail.contains("Until loop exhausted"), detail);
        assertTrue(detail.contains("stepRepeatMaxCount"), detail);
        assertFalse(requireScenario().isScenarioFailed());
    }

    @Test
    void untilExhaustedByTimeHardFails() {
        Throwable exhausted = runUntil(", until \"\" equals \"done\":", 100_000, Duration.ofMillis(1));
        String detail = detail(exhausted);
        assertTrue(detail.contains("Until loop exhausted"), detail);
        assertTrue(detail.contains("stepRepeatMaxTime"), detail);
        assertFalse(requireScenario().isScenarioFailed());
    }

    @Test
    void elementWaitHonorsRepeatCount() {
        navigateHome();
        ControlCallResult<Object> result = waitForMissingElement(0, Duration.ofHours(1));
        String detail = detail(result);
        assertEquals(ControlCallStatus.FAILED, result.status(), detail);
        assertTrue(detail.contains("Element wait exhausted"), detail);
        assertTrue(detail.contains("stepRepeatMaxCount"), detail);
        assertFalse(requireScenario().isScenarioFailed());
    }

    @Test
    void elementWaitHonorsRepeatTime() {
        navigateHome();
        ControlCallResult<Object> result = waitForMissingElement(100_000, Duration.ofMillis(1));
        String detail = detail(result);
        assertEquals(ControlCallStatus.FAILED, result.status(), detail);
        assertTrue(detail.contains("Element wait exhausted"), detail);
        assertTrue(detail.contains("stepRepeatMaxTime"), detail);
        assertFalse(requireScenario().isScenarioFailed());
    }

    @Test
    void emptyCommaStepIsAClearError() {
        CurrentScenarioState state = requireScenario();
        ControlCallResult<Object> result = DynamicControl.executeStep(",");
        String detail = detail(result);
        assertEquals(ControlCallStatus.FAILED, result.status(), detail);
        assertTrue(detail.contains("no phrase"), detail);
        assertFalse(detail.contains("IndexOutOfBoundsException"), detail);
        assertFalse(state.isScenarioFailed());
    }

    @Test
    void softFailThrowsAndLaterObserverStepsStillRun() {
        CurrentScenarioState state = requireScenario();
        ControlCallResult<Object> result = DynamicControl.executeStep(
                "SOFT FAIL SCENARIO \"area b soft warning\""
        );
        String detail = detail(result);
        assertEquals(ControlCallStatus.FAILED, result.status(), detail);
        assertTrue(detail.contains("area b soft warning"), detail);
        assertFalse(state.isScenarioFailed());
        ControlCallResult<Object> save = DynamicControl.executeStep(
                ", save \"continued\" as \"areaBSoftObserver\""
        );
        assertTrue(save.successful(), () -> String.valueOf(save.error()));
        ControlCallResult<Object> ensure = DynamicControl.executeStep(
                ", ensure \"<areaBSoftObserver>\" equals \"continued\""
        );
        assertTrue(ensure.successful(), () -> String.valueOf(ensure.error()));
        assertFalse(state.isScenarioFailed());
    }

    @Test
    void blankDynamicArgumentIsNotReportedAsWithArgument() {
        StepCreationException blank = assertStepCreation("this step does not exist", "");
        assertFalse(blank.getMessage().contains("with argument"), blank.getMessage());
        StepCreationException present = assertStepCreation("this step does not exist", "arg");
        assertTrue(present.getMessage().contains("with argument 'arg'"), present.getMessage());
    }

    private static Throwable runUntil(String stepText, int maxIterations, Duration maxTime) {
        CurrentScenarioState state = requireScenario();
        StepExtension previousStep = state.getCurrentStep();
        boolean previousEnd = state.endCurrentScenario;
        int previousFailures = state.stepFailures.size();
        int previousIterations = StepData.globalMaxIterations;
        Duration previousTimeout = StepData.globalTimeoutSeconds;
        StepData.globalMaxIterations = maxIterations;
        StepData.globalTimeoutSeconds = maxTime;
        try {
            ControlCallResult<StepExtension> created = DynamicControl.createStep(stepText);
            assertTrue(created.successful(), () -> String.valueOf(created.error()));
            state.runStep(created.value());
            throw new AssertionError("until loop returned without exhausting its limit");
        } catch (Throwable thrown) {
            if (thrown instanceof AssertionError && thrown.getMessage() != null
                    && thrown.getMessage().startsWith("until loop returned")) {
                throw thrown;
            }
            return thrown;
        } finally {
            StepData.globalMaxIterations = previousIterations;
            StepData.globalTimeoutSeconds = previousTimeout;
            while (state.stepFailures.size() > previousFailures) {
                state.stepFailures.remove(state.stepFailures.size() - 1);
            }
            state.endCurrentScenario = previousEnd;
            setProperty(state, "currentStep", previousStep);
            setProperty(state, "isScenarioHardFail", false);
            setProperty(state, "isScenarioSoftFail", false);
            setProperty(state, "isScenarioComplete", false);
        }
    }

    private static void navigateHome() {
        ControlCallResult<Object> result = DynamicControl.executeStep("navigate to: URL.home");
        assertTrue(result.successful(), () -> String.valueOf(result.error()));
    }

    private static ControlCallResult<Object> waitForMissingElement(int maxIterations, Duration maxTime) {
        int previousIterations = StepData.globalMaxIterations;
        Duration previousTimeout = StepData.globalTimeoutSeconds;
        StepData.globalMaxIterations = maxIterations;
        StepData.globalTimeoutSeconds = maxTime;
        try {
            return DynamicControl.executeStep(", wait the \"No Such Area B\" Button");
        } finally {
            StepData.globalMaxIterations = previousIterations;
            StepData.globalTimeoutSeconds = previousTimeout;
        }
    }

    private static StepCreationException assertStepCreation(String stepText, String argument) {
        try {
            DynamicExecution.runCustomStep(stepText, argument);
        } catch (StepCreationException exception) {
            return exception;
        } catch (RuntimeException exception) {
            throw new AssertionError(
                    "expected StepCreationException but got " + exception,
                    exception
            );
        }
        throw new AssertionError("expected step creation to fail for '" + stepText + "'");
    }

    private static String detail(Throwable thrown) {
        assertNotNull(thrown);
        StringBuilder text = new StringBuilder();
        Throwable current = thrown;
        while (current != null) {
            text.append(current.getClass().getName()).append(": ");
            text.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return text.toString();
    }

    private static String detail(ControlCallResult<Object> result) {
        if (result.error() == null) {
            return String.valueOf(result.status());
        }
        return result.error().message() + "\n" + result.error().stackTrace();
    }

    private static CurrentScenarioState requireScenario() {
        CurrentScenarioState state = getCurrentScenarioState();
        assertNotNull(state);
        assertFalse(state.isScenarioFailed(), () -> String.valueOf(getProperty(state, "isScenarioHardFail")));
        return state;
    }
}
