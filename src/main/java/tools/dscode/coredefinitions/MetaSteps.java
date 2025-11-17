package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;

import static tools.dscode.common.GlobalConstants.defaultMatchFlag;
import static tools.dscode.common.util.DebugUtils.printDebug;

//import static tools.dscode.tools.dscode.coredefinitions.MetaSteps.defaultMatchFlag;

public class MetaSteps  extends CoreSteps {

    public static final String RUN_SCENARIO = "RUN SCENARIO:";

    @Given("^" + RUN_SCENARIO + "(.*)$")
    public static void runScenario(String scenarioName) {
        printDebug("@@scenarioName: " + scenarioName);
        // place Holder
    }


    @Given("^" + defaultMatchFlag + "(.*)$")
    public static void matchDefault(String text) {
        printDebug("@@DEFAULT_DEFINITION_text:: " + text);
    }

}
