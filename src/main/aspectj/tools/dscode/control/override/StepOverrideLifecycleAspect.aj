package tools.dscode.control.override;

import io.cucumber.core.runner.CurrentScenarioState;

public aspect StepOverrideLifecycleAspect {
    after(CurrentScenarioState state):
            execution(void io.cucumber.core.runner.CurrentScenarioState.startScenarioRun())
            && this(state) {
        StepOverrideRegistry.clear(state.id.toString());
    }
}
