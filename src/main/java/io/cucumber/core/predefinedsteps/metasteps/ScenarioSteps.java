package io.cucumber.core.predefinedsteps.metasteps;

import io.cucumber.java.en.Given;

//import static io.pickleball.cacheandstate.PrimaryScenarioData.getCurrentScenario;
import static io.cucumber.utilities.GeneralUtilities.getStringTimeStamp;
import static io.cucumber.utilities.GeneralUtilities.waitTime;
import static io.pickleball.cacheandstate.GlobalCache.getState;

public class ScenarioSteps {
    @Given("^Scenario:(.*)$")
    public MetaStepData ScenarioRun(String scenarioData) {
//        waitTime(1000L);
        // Parse command-line arguments into RuntimeOptions
        String[] argv = {
                "src/test/resources/features",
                "--tags", "@zzs or @zzs1 or @zzs9"
        };
        getState().getCurrentScenario().createComponentScenario(argv);
        return (new MetaStepData()).set("scenarioData", scenarioData);
    }
}
