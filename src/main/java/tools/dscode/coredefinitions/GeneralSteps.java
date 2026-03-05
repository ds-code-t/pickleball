package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.ParameterType;
import io.cucumber.java.en.Given;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.DefinitionFlags;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.reporting.logging.Log;
import tools.dscode.common.status.SoftRuntimeException;

import java.time.Duration;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.cucumber.core.runner.CurrentScenarioState.getScenarioObject;
import static io.cucumber.core.runner.CurrentScenarioState.registerScenarioObject;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.GlobalState.lifecycle;
import static io.cucumber.core.runner.GlobalState.pickleballLog;
import static tools.dscode.common.GlobalConstants.HARD_ERROR_STEP;
import static tools.dscode.common.GlobalConstants.INFO_STEP;
import static tools.dscode.common.GlobalConstants.NEXT_SIBLING_STEP;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;
import static tools.dscode.common.GlobalConstants.SCENARIO_STEP;
import static tools.dscode.common.GlobalConstants.SOFT_ERROR_STEP;
import static tools.dscode.common.annotations.DefinitionFlag._NO_LOGGING;
import static tools.dscode.common.domoperations.LeanWaits.waitForPageReady;
import static tools.dscode.common.domoperations.SeleniumUtils.ensureDevToolsPort;
import static tools.dscode.common.domoperations.SeleniumUtils.waitMilliseconds;
import static tools.dscode.common.mappings.BracketLiteralMasker.getAndResolveKeyWithMasking;
import static tools.dscode.common.reporting.logging.LogForwarder.stepInfo;
import static tools.dscode.common.variables.RunVars.resolveVarOrDefault;


public class GeneralSteps extends CoreSteps {

    @BeforeAll
    public static void beforeAll() {

        lifecycle.fire(Phase.BEFORE_CUCUMBER_RUN);
    }

    @AfterAll
    public static void afterAll() {
        Log.global().closeAll();
        lifecycle.fire(Phase.AFTER_CUCUMBER_RUN);
        pickleballLog.stop();
    }



    @Given("navigate {returnStepParameter}")
    public void navigateBrowser(ChromiumDriver driver, List<String> list) {
        driver.get(list.getFirst());
        waitForPageReady(driver, Duration.ofSeconds(60));
    }





    @Given("^" + SCENARIO_STEP + "\\s*(?:.*)$")
    public static void scenarioStep() {

    }

    @DefinitionFlags(DefinitionFlag.RUN_METHOD_DIRECTLY)
    @Given(ROOT_STEP)
    public static void rootStep() {

        getCurrentScenarioState().startScenarioRun();
    }

    @Given(INFO_STEP)
    public static void infoStep(String message) {
        stepInfo(message);
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
        stepInfo("Skipping step: " + message);
    }


    @Given("^print (.*)$")
    public static void printVal(String message) {
        stepInfo("PRINT: " + message);
    }


    @DefinitionFlags(_NO_LOGGING)
    @Given(NEXT_SIBLING_STEP)
    public static void NEXT_SIBLING_STEP() {

    }

}

