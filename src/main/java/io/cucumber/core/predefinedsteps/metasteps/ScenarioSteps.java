package io.cucumber.core.predefinedsteps.metasteps;

import io.cucumber.java.en.Given;

//import static io.cucumber.core.runtime.PrimaryScenarioData.getCurrentScenario;
import static io.cucumber.utilities.GeneralUtilities.getStringTimeStamp;
import static io.cucumber.utilities.GeneralUtilities.waitTime;

public class ScenarioSteps {
    @Given("^Scenario:(.*)$")
    public MetaStepData ScenarioRun(String scenarioData) {
//        waitTime(1000L);
        // Parse command-line arguments into RuntimeOptions
        String[] argv = {
                "src/test/resources/features",
                "--tags", "@zzs or @zzs1"
        };
        System.out.println("@@Scenario:111 " + getStringTimeStamp());
//        getCurrentScenario().createComponentScenario(argv);
        System.out.println("@@Scenario:22 " + getStringTimeStamp());

        return (new MetaStepData()).set("scenarioData", scenarioData);
    }
}
