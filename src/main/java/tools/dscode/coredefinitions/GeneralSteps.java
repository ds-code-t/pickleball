package tools.dscode.coredefinitions;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.java.ParameterType;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.ReturnStep;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.edge.EdgeDriver;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.DefinitionFlags;
import tools.dscode.common.status.SoftRuntimeException;
import tools.dscode.common.treeparsing.DictionaryA;
import tools.dscode.common.treeparsing.LineExecution;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.common.GlobalConstants.HARD_ERROR_STEP;
import static tools.dscode.common.GlobalConstants.INFO_STEP;
//import static tools.dscode.common.GlobalConstants.RETURN_STEP_FLAG;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;
import static tools.dscode.common.GlobalConstants.SCENARIO_STEP;
import static tools.dscode.common.GlobalConstants.SOFT_ERROR_STEP;
import static tools.dscode.common.domoperations.DriverFactory.createChromeDriver;

public class GeneralSteps extends CoreSteps {

    @ReturnStep("^@(.*)-(.*)$")
    public void test(String stepText) {
        // step body here
    }

    @ParameterType("^(@\\(.*)\\)$")
    public Object returnStepParameter(String stepText) {
        StepExtension currentStep = getCurrentScenarioState().getCurrentStep();
        StepExtension modifiedStep = currentStep.modifyStepExtension(stepText);
        return modifiedStep.runAndGetReturnValue();
    }


    @Given("^" + SCENARIO_STEP + "(.*)$")
    public static void scenarioStep(String scenarioName) {
        System.out.println("Running Scenario: " + scenarioName);
    }

    @DefinitionFlags(DefinitionFlag.RUN_METHOD_DIRECTLY)
    @Given(ROOT_STEP)
    public static void rootStep() {
        System.out.println("Starting Scenario Run");
        getCurrentScenarioState().startScenarioRun();
    }

    @Given(INFO_STEP)
    public static void infoStep(String message) {
        System.out.println(message);
    }

    @Given(HARD_ERROR_STEP)
    public static void hardFailStep(String message) {
        throw new RuntimeException(message);
    }

    @Given(SOFT_ERROR_STEP)
    public static void softFailStep(String message) {
        throw new SoftRuntimeException(message);
    }


    @Given("^print (.*)$")
    public static void printVal(String message) {
        System.out.println("PRINT: " + message);
    }

}

