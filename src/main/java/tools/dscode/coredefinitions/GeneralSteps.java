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


import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.cucumber.core.runner.CurrentScenarioState.getScenarioObject;
import static io.cucumber.core.runner.CurrentScenarioState.registerScenarioObject;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.common.GlobalConstants.HARD_ERROR_STEP;
import static tools.dscode.common.GlobalConstants.INFO_STEP;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;
import static tools.dscode.common.GlobalConstants.SCENARIO_STEP;
import static tools.dscode.common.GlobalConstants.SOFT_ERROR_STEP;

import static tools.dscode.common.domoperations.LeanWaits.waitForPageReady;


import com.fasterxml.jackson.databind.ObjectMapper;


public class GeneralSteps extends CoreSteps {

//    @ReturnStep("^@(.*)-(.*)$")
//    public void test(String stepText) {
//        // step body here
//    }

    public static ChromiumDriver getBrowser(String browserName){
        return (ChromiumDriver) returnStepParameter(browserName);
    }

    @Given("navigate {returnStepParameter}")
    public void navigateBrowser(ChromiumDriver driver, List<String> list) {
        driver.get(list.getFirst());
        waitForPageReady(driver, Duration.ofSeconds(60));
    }


    @ParameterType("\\$\\(([^()]+)\\)")
    public static Object returnStepParameter(String stepText) {
        System.out.println("@@stepText1: " + stepText);

        String getKey = "";
        String putKey = "";
        if (stepText.contains(":")) {
            getKey = stepText.substring(0, stepText.indexOf(":"));
            stepText = stepText.substring(stepText.indexOf(":") + 1);
            Object existingObject2 = getScenarioObject(getKey);
            if (existingObject2 != null) return existingObject2;
        } else if (stepText.contains("=")) {
            putKey = stepText.substring(0, stepText.indexOf("="));
            stepText = stepText.substring(stepText.indexOf("=") + 1);
        }
        else {
            Object existingObject1 = getScenarioObject(stepText);
            if (existingObject1 != null) return existingObject1;
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
            registerScenarioObject(putKey, returnValue);
            return returnValue;
        }
        if (getKey.isEmpty()) return returnValue;

        registerScenarioObject(getKey, returnValue);
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
        System.out.println("@@config: " + config);
        ChromeOptions options = new ChromeOptions();
        options.setCapability("goog:chromeOptions", config);

        ChromeDriver chromeDriver = new ChromeDriver(options);
        System.out.println("@@chromeDriver1: " + chromeDriver);
//        registerScenarioObject("chrome", chromeDriver);
        System.out.println("@@11");
        registerScenarioObject("browser", chromeDriver);
        System.out.println("@@chromeDriver2: " + chromeDriver);
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

