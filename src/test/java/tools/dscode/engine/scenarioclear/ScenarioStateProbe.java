package tools.dscode.engine.scenarioclear;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.java.en.Given;

/** Sees whether this step still has the Cucumber scenario thread local. */
public class ScenarioStateProbe {
    static volatile boolean liveDuringStep;

    @Given("scenario state is live")
    public void probe() {
        liveDuringStep = CurrentScenarioState.currentScenarioState.get() != null;
    }
}
