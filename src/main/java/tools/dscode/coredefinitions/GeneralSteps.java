package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.java.ParameterType;
import io.cucumber.java.en.Given;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
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
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.GlobalConstants.HARD_ERROR_STEP;
import static tools.dscode.common.GlobalConstants.INFO_STEP;
import static tools.dscode.common.GlobalConstants.NEXT_SIBLING_STEP;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;
import static tools.dscode.common.GlobalConstants.SCENARIO_STEP;
import static tools.dscode.common.GlobalConstants.SOFT_ERROR_STEP;
import static tools.dscode.common.annotations.DefinitionFlag._NO_LOGGING;
import static tools.dscode.common.domoperations.LeanWaits.waitForPageReady;
import static tools.dscode.common.domoperations.SeleniumUtils.ensureDevToolsPort;


public class GeneralSteps extends CoreSteps {

//    @ReturnStep("^@(.*)-(.*)$")
//    public void test(String stepText) {
//        // step body here
//    }

    public static ChromiumDriver getBrowser() {
        return getBrowser("BROWSER");
    }
    public static ChromiumDriver getBrowser(String browserName) {

        Object returnObject = getScenarioObject(browserName);
        if (returnObject != null) return (ChromiumDriver) returnObject;
        returnObject = returnStepParameter(browserName);
        if (returnObject != null) return (ChromiumDriver) returnObject;
        return (ChromiumDriver) returnStepParameter("CHROME");
    }

    @Given("navigate {returnStepParameter}")
    public void navigateBrowser(ChromiumDriver driver, List<String> list) {
        driver.get(list.getFirst());
        waitForPageReady(driver, Duration.ofSeconds(60));
    }

    public static Object returnStepParameter(String stepText) {
        return returnStepParameter(stepText, null);
    }

    //    @ParameterType("\\$\\(([^()]+)\\)")
    @ParameterType("([A-Za-z0-9_-]+)(:([A-Za-z0-9_-]+))?")
    public static Object returnStepParameter(String stepText, String key) {
        StepExtension currentStep = getRunningStep();
        Object existingObject = getScenarioObject(stepText);
        if (key == null || key.isBlank()) {
            if (existingObject != null) return existingObject;
            StepExtension modifiedStep = currentStep.modifyStepExtension("$" + stepText);
            modifiedStep.argument = currentStep.argument;
            Object returnValue = modifiedStep.runAndGetReturnValue();
            registerScenarioObject(stepText, returnValue);
            return returnValue;
        } else {
            Object existingObjectFromKey = getScenarioObject(key);
            if (existingObjectFromKey != null) return existingObjectFromKey;
            StepExtension modifiedStep = currentStep.modifyStepExtension("$" + stepText);
            modifiedStep.argument = currentStep.argument;
            Object returnValue = modifiedStep.runAndGetReturnValue();
            registerScenarioObject(key, returnValue);
            return returnValue;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    //    @Given("(?i)^@chrome$")
    @Given("$BROWSER")
    @Given("$CHROME")
    public ChromeDriver getChrome() throws Exception {

        StepExtension currentStep = getRunningStep();
        String json = !(currentStep.argument instanceof DocStringArgument) ? (String) currentStep.getStepParsingMap().getAndResolve("configs.chrome") : currentStep.argument.getValue().toString();
        if (Objects.isNull(json)) throw new RuntimeException("Chrome Driver Configuration not found");
        Map<String, Object> map = MAPPER.readValue(json, Map.class);
        ChromeOptions options = new ChromeOptions();
        map.forEach(options::setCapability);
        if (getCurrentScenarioState().debugBrowser) {
            ensureDevToolsPort(options, "chrome");
        }
        ChromeDriver chromeDriver = new ChromeDriver(options);
        registerScenarioObject("browser", chromeDriver);
        return chromeDriver;
    }

    @Given("$EDGE")
    public EdgeDriver getEdge() throws Exception {
        StepExtension currentStep = getRunningStep();
        String json = !(currentStep.argument instanceof DocStringArgument) ? (String) currentStep.getStepParsingMap().getAndResolve("configs.edge") : currentStep.argument.getValue().toString();
        if (Objects.isNull(json)) throw new RuntimeException("Edge Driver Configuration not found");
        Map<String, Object> map = MAPPER.readValue(json, Map.class);
        EdgeOptions options = new EdgeOptions();
        map.forEach(options::setCapability);
        if (getCurrentScenarioState().debugBrowser) {
            ensureDevToolsPort(options, "edge");
        }
        EdgeDriver edgeDriver = new EdgeDriver(options);
        registerScenarioObject("browser", edgeDriver);
        return edgeDriver;
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

    @Given("^SKIPPING: (.*)$")
    public static void skippedStep(String message) {
        System.out.println("Skipping step: " + message);
    }



    @Given("^print (.*)$")
    public static void printVal(String message) {
        System.out.println("PRINT: " + message);
    }


    @DefinitionFlags(_NO_LOGGING)
    @Given(NEXT_SIBLING_STEP)
    public static void NEXT_SIBLING_STEP() {
        System.out.println("NEXT_SIBLING_STEP: ");
    }

}

