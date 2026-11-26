package tools.dscode.engine.runtime;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepData;
import io.cucumber.core.runner.StepExtension;
import org.junit.jupiter.api.Test;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.coredefinitions.DynamicSteps;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.match.StepRegistry;
import tools.dscode.engine.model.EngineScenario;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepCeilingTest {
    @Test
    void stepMaxIterationsStopsABlockUntil() {
        String message = exhaust(", until \"\" equals \"done\":", 2, 100);
        assertTrue(message.contains("max_iterations"), message);
        assertTrue(message.contains("2"), message);
        assertFalse(message.contains("pkb_stepRepeatMaxCount"), message);
    }

    @Test
    void nullColumnUsesTheRunWideRepeatCap() {
        String message = exhaust(", until \"\" equals \"done\":", null, 0);
        assertTrue(message.contains("pkb_stepRepeatMaxCount"), message);
        assertFalse(message.contains("max_iterations"), message);
    }

    private static String exhaust(String text, Integer stepMax, int globalMax) {
        Integer previousIterations = StepData.overrideMaxIterations(globalMax);
        Duration previousTimeout = StepData.overrideTimeout(Duration.ofHours(1));
        CurrentScenarioState previous = CurrentScenarioState.currentScenarioState.get();
        try (ExecutionContext context = ExecutionContext.open(EngineScenario.of("ceilings", List.of()))) {
            CurrentScenarioState state = new CurrentScenarioState("ceilings", "ceilings");
            CurrentScenarioState.currentScenarioState.set(state);
            context.attachLegacy(state);
            state.scenarioLog = Entry.of("ceilings").tag("Scenario").start();
            context.resource("registry", new StepRegistry().scan(DynamicSteps.class));
            context.resource("instances", new IdentityHashMap<Class<?>, Object>());
            ScenarioStep root = new ScenarioStep("ceilings");
            StepExtension step = new StepExtension("* ", text);
            step.stepMaxIterations = stepMax;
            step.parentStep = root;
            step.setStepParsingMap(state.getParsingMap());
            RuntimeException exhausted = assertThrows(RuntimeException.class, () -> state.runStep(step));
            return detail(exhausted);
        } finally {
            StepData.restoreMaxIterations(previousIterations);
            StepData.restoreTimeout(previousTimeout);
            CurrentScenarioState.currentScenarioState.set(previous);
        }
    }

    private static String detail(Throwable thrown) {
        StringBuilder text = new StringBuilder();
        Throwable current = thrown;
        while (current != null) {
            text.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return text.toString();
    }
}
