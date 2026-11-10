package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;

import static tools.dscode.common.GlobalConstants.defaultMatchFlag;

//import static tools.dscode.tools.dscode.coredefinitions.MetaSteps.defaultMatchFlag;

public class MetaSteps  extends CoreSteps {

    public static final String RUN_SCENARIO = "\u207A RUN SCENARIO:";

    @Given("^\u207A RUN SCENARIO:(.*)$")
    public static void runScenario(String scenarioName) {

        // place Holder
    }


    @Given("^\u207A-DEFAULT_DEFINITION_(.*)$")
    public static void matchDefault(String text) {

    }

}
