package tools.dscode.engine.adapter.cucumber;

import io.cucumber.core.runner.CurrentScenarioState;

/** Parks the Cucumber scenario thread local around a native run. */
public final class CucumberLane {
    private CucumberLane() {
    }

    /** Removes the thread local when this call is the top-level native run. */
    public static Object park(boolean topLevel) {
        if (!topLevel) return null;
        CurrentScenarioState parked = CurrentScenarioState.currentScenarioState.get();
        CurrentScenarioState.currentScenarioState.remove();
        return parked;
    }

    public static void restore(boolean topLevel, Object parked) {
        if (topLevel && parked instanceof CurrentScenarioState state) {
            CurrentScenarioState.currentScenarioState.set(state);
        }
    }
}
