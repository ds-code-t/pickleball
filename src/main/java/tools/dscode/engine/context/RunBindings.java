package tools.dscode.engine.context;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepBase;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;

/**
 * Scenario lookups for mapping, phrases, and assertions.
 * A live Cucumber test case is the only shim:
 * every other caller uses the {@link ExecutionContext} passed in.
 */
public final class RunBindings {
    private final ExecutionContext context;

    private RunBindings(ExecutionContext context) {
        this.context = context;
    }

    /** {@code context} wins. Null uses the thread's open native context. */
    public static RunBindings of(ExecutionContext context) {
        return new RunBindings(context != null ? context : ExecutionContext.current());
    }

    /** True only while the Cucumber adapter has a real test case on this thread. */
    public static boolean cucumberScenarioLive() {
        return GlobalState.cucumberScenarioLive();
    }

    public ExecutionContext context() {
        return context;
    }

    public CurrentScenarioState scenarioState() {
        if (cucumberScenarioLive()) {
            return CurrentScenarioState.currentScenarioState.get();
        }
        if (context != null && context.legacy() != null) {
            return context.legacy();
        }
        CurrentScenarioState parked = CurrentScenarioState.currentScenarioState.get();
        if (parked != null) {
            return parked;
        }
        return ExecutionContext.legacyOrNull();
    }

    public StepExtension runningStep() {
        if (cucumberScenarioLive()) {
            return GlobalState.getRunningStep();
        }
        // Cucumber reaches this through GlobalState.getRunningStep, which the
        // control aspect redirects to the detached step. Native has no aspect.
        StepExtension override = tools.dscode.common.control.ControlExecutionScope.currentStepOverride();
        if (override != null) return override;
        CurrentScenarioState state = scenarioState();
        return state == null ? null : state.getCurrentStep();
    }

    public ScenarioStep rootScenarioStep() {
        if (cucumberScenarioLive()) {
            return GlobalState.getRootScenarioStep();
        }
        if (context != null && context.rootStep() != null) {
            return context.rootStep();
        }
        ExecutionContext current = ExecutionContext.current();
        if (current != null && current.rootStep() != null) {
            return current.rootStep();
        }
        return null;
    }

    public ScenarioStep closestScenarioStep() {
        if (cucumberScenarioLive()) {
            return GlobalState.getClosestScenarioStepAncestor();
        }
        StepBase step = runningStep();
        while (step != null) {
            if (step instanceof ScenarioStep scenario) {
                return scenario;
            }
            step = step.parentStep;
        }
        return rootScenarioStep();
    }

    public Phrase currentPhrase() {
        if (cucumberScenarioLive()) {
            return GlobalState.getRunningPhrase();
        }
        CurrentScenarioState state = scenarioState();
        return state == null ? null : state.currentPhrase;
    }

    public void currentPhrase(Phrase phrase) {
        CurrentScenarioState state = scenarioState();
        if (state != null) {
            state.currentPhrase = phrase;
        }
    }
}
