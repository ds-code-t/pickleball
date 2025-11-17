package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.java.ParameterType;
import io.cucumber.java.en.Given;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumDriver;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.DefinitionFlags;
import tools.dscode.common.status.SoftRuntimeException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.cucumber.core.runner.CurrentScenarioState.getScenarioObject;
import static io.cucumber.core.runner.CurrentScenarioState.normalizeRegistryKey;
import static io.cucumber.core.runner.CurrentScenarioState.registerScenarioObject;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.common.GlobalConstants.HARD_ERROR_STEP;
import static tools.dscode.common.GlobalConstants.INFO_STEP;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;
import static tools.dscode.common.GlobalConstants.SCENARIO_STEP;
import static tools.dscode.common.GlobalConstants.SOFT_ERROR_STEP;
import static tools.dscode.common.domoperations.LeanWaits.waitForPageReady;
import static tools.dscode.common.domoperations.SeleniumUtils.portFromString;
import static tools.dscode.common.util.DebugUtils.printDebug;


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
        printDebug("@@stepText1: " + stepText);

        String getKey = "";
        if (stepText.contains(":")) {
            getKey = stepText.substring(0, stepText.indexOf(":"));
            stepText = stepText.substring(stepText.indexOf(":") + 1);
            Object existingObject = getScenarioObject(getKey);
            if (existingObject != null) return existingObject;
        }
        else {
            Object existingObject = getScenarioObject(stepText);
            if (existingObject != null) return existingObject;
        }
        stepText = "$" + stepText;
        printDebug("@@stepText2: " + stepText);
        StepExtension currentStep = getCurrentScenarioState().getCurrentStep();
        printDebug("@@currentStep: " + currentStep);
        StepExtension modifiedStep = currentStep.modifyStepExtension(stepText);
        printDebug("@@currentStep.argument== " + currentStep.argument);
        printDebug("@@modifiedStep.argument== " + modifiedStep.argument);
        modifiedStep.argument = currentStep.argument;
        if(!getKey.isEmpty())
            modifiedStep.put("_getKey" , normalizeRegistryKey(getKey));

        printDebug("@@modifiedStep: " + modifiedStep);
        Object returnValue = modifiedStep.runAndGetReturnValue();
        printDebug("@@--returnValue: " + returnValue.getClass());
        registerScenarioObject(stepText, returnValue);

        if (getKey.isEmpty()) return returnValue;

        registerScenarioObject(getKey, returnValue);
        return returnValue;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    //    @Given("(?i)^@chrome$")
    @Given("$CHROME")
    public ChromeDriver getChrome() throws Exception {
        printDebug("@@getChrome");
        StepExtension currentStep = getCurrentScenarioState().getCurrentStep();
        printDebug("@@currentStep: " + currentStep);
        printDebug("@@currentStep.argument.: " + currentStep.argument);
        String json = currentStep.argument == null || !(currentStep.argument instanceof DocStringArgument) ? (String) currentStep.getStepParsingMap().getAndResolve("configs.chrome") : currentStep.argument.getValue().toString();
        printDebug("@@json: " + json);
        if (Objects.isNull(json)) throw new RuntimeException("Chrome Driver Configuration not found");
        Map<String, Object> config = MAPPER.readValue(json, Map.class);
        printDebug("@@config: " + config);
        ChromeOptions options = new ChromeOptions();
        options.setCapability("goog:chromeOptions", config);

        if(getCurrentScenarioState().debugBrowser) {
            String port = (String) currentStep.getStepNodeMap().get("_getKey");
            if(port == null) port = "chrome";
            options.setExperimentalOption("debuggerAddress", "127.0.0.1:" + portFromString(port));
            options.setExperimentalOption("detach", true);   // keep browser open after test
            options.setCapability("dontQuit", true);
        }

        ChromeDriver chromeDriver = new ChromeDriver(options);
        printDebug("@@chromeDriver1: " + chromeDriver);
//        registerScenarioObject("chrome", chromeDriver);
        printDebug("@@11");
        registerScenarioObject("browser", chromeDriver);
        printDebug("@@chromeDriver2: " + chromeDriver);
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

