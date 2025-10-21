package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.DefinitionFlags;

import static tools.dscode.common.GlobalConstants.META_FLAG;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;
import static tools.dscode.state.ScenarioState.getScenarioState;

public class GeneralSteps  extends CoreSteps {

//    @DefinitionFlags(DefinitionFlag.NO_LOGGING)
    @Given(ROOT_STEP)
    public static void rootStep() {
        System.out.println("@@ROOT_STEP!!");
    }

    @Given("MESSAGE:{string}")
    public static void setValues(String message) {
        System.out.println("MESSAGE: " + message);
        Throwable t = getScenarioState().getCurrentStep().storedThrowable;
        if (t != null) {
            getScenarioState().getCurrentStep().storedThrowable = null;
            throw new RuntimeException(t);
        }
    }

    @Given("^print (.*)$")
    public static void printVal(String message) {
        System.out.println("PRINT: " + message);
    }

}

