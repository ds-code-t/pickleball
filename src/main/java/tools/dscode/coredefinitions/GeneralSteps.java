package tools.dscode.coredefinitions;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.java.ParameterType;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.ReturnStep;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.edge.EdgeDriver;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.DefinitionFlags;
import tools.dscode.common.status.SoftRuntimeException;
import tools.dscode.common.treeparsing.DictionaryA;
import tools.dscode.common.treeparsing.LineExecution;

import java.util.Map;
import java.util.Objects;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.common.GlobalConstants.HARD_ERROR_STEP;
import static tools.dscode.common.GlobalConstants.INFO_STEP;
//import static tools.dscode.common.GlobalConstants.RETURN_STEP_FLAG;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;
import static tools.dscode.common.GlobalConstants.SCENARIO_STEP;
import static tools.dscode.common.GlobalConstants.SOFT_ERROR_STEP;
import static tools.dscode.common.domoperations.DriverFactory.createChromeDriver;
import static tools.dscode.registry.GlobalRegistry.getLocal;
import static tools.dscode.registry.GlobalRegistry.putLocal;
import static tools.dscode.registry.GlobalRegistry.registerLocal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.Given;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

public class GeneralSteps extends CoreSteps {

//    @ReturnStep("^@(.*)-(.*)$")
//    public void test(String stepText) {
//        // step body here
//    }

    @ParameterType("\\$\\(([^()]+)\\)")
    public Object returnStepParameter(String stepText) {
        System.out.println("@@stepText1: " + stepText);

        String getKey = "";
        String putKey = "";
        if (stepText.contains(":")) {
            getKey = stepText.substring(0, stepText.indexOf(":"));
            Object existingObject = getLocal(getKey);
            if (existingObject != null) return existingObject;
            stepText = stepText.substring(stepText.indexOf(":") + 2);
        } else if (stepText.contains("=")) {
            putKey = stepText.substring(0, stepText.indexOf("="));
            stepText = stepText.substring(stepText.indexOf(":") + 2);
        }
        stepText = "$" + stepText;
        System.out.println("@@stepText2: " + stepText);
        StepExtension currentStep = getCurrentScenarioState().getCurrentStep();
        System.out.println("@@currentStep: " + currentStep);
        StepExtension modifiedStep = currentStep.modifyStepExtension(stepText);
        System.out.println("@@currentStep.argument== " + currentStep.argument);
        System.out.println("@@modifiedStep.argument== " + modifiedStep.argument);
        modifiedStep.argument = currentStep.argument;

        System.out.println("@@modifiedStep: " + modifiedStep);
        Object returnValue = modifiedStep.runAndGetReturnValue();
        System.out.println("@@--returnValue: " + returnValue.getClass());
        if (!putKey.isEmpty()) {
            putLocal(putKey, returnValue);
            return returnValue;
        }
        if (getKey.isEmpty()) return returnValue;

        putLocal(getKey, returnValue);
        return returnValue;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    //    @Given("(?i)^@chrome$")
    @Given("$CHROME")
    public ChromeDriver getChrome() throws Exception {
        System.out.println("@@getChrome");
        StepExtension currentStep = getCurrentScenarioState().getCurrentStep();
        System.out.println("@@currentStep: " + currentStep);
        System.out.println("@@currentStep.argument.: " + currentStep.argument);
        String json = currentStep.argument == null ? (String) currentStep.getStepParsingMap().getAndResolve("configs.chrome") : currentStep.argument.getValue().toString();
        System.out.println("@@json: " + json);
        if (Objects.isNull(json)) throw new RuntimeException("Chrome Driver Configuration not found");
        Map<String, Object> config = MAPPER.readValue(json, Map.class);

        ChromeOptions options = new ChromeOptions();
        options.setCapability("goog:chromeOptions", config);

        ChromeDriver chromeDriver = new ChromeDriver(options);
        putLocal("chrome", chromeDriver);
        return chromeDriver;
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

