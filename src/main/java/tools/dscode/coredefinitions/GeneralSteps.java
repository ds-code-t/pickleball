package tools.dscode.coredefinitions;


import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.DefinitionFlags;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.reporting.logging.Log;
import tools.dscode.common.exceptions.SoftRuntimeException;

import java.lang.reflect.Method;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.lifecycle;
import static io.cucumber.core.runner.GlobalState.pickleballLog;
import static tools.dscode.common.GlobalConstants.HARD_ERROR_STEP;
import static tools.dscode.common.GlobalConstants.INFO_STEP;
import static tools.dscode.common.GlobalConstants.NEXT_SIBLING_STEP;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;
import static tools.dscode.common.GlobalConstants.SCENARIO_STEP;
import static tools.dscode.common.GlobalConstants.SOFT_ERROR_STEP;
import static tools.dscode.common.annotations.DefinitionFlag._NO_LOGGING;
import static tools.dscode.common.reporting.logging.LogForwarder.stepInfo;


public class GeneralSteps extends CoreSteps {

    public static Method rootStepMethod;
    public static Method scenarioStepMethod;

    static {
        try {
            rootStepMethod = GeneralSteps.class.getMethod("rootStep");
            scenarioStepMethod = GeneralSteps.class.getMethod("scenarioStep");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Given("^" + SCENARIO_STEP + "\\s*(?:.*)$")
    public static void scenarioStep() {

    }


    @DefinitionFlags(DefinitionFlag.RUN_METHOD_DIRECTLY)
    @Given(ROOT_STEP)
    public static void rootStep() {
        getCurrentScenarioState().startScenarioRun();
    }



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


    @Given("^"+INFO_STEP+ "(.*)$")
    public static void infoStep(String message) {
        stepInfo(message);
    }

    @Given("^"+ HARD_ERROR_STEP+ "(.*)$")
    public static void hardFailStep(String message) {
        throw new RuntimeException(message);
    }

    @Given("^"+SOFT_ERROR_STEP+ "(.*)$")
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

    @Given("(?i)^ScenarioName$")
    public static String getScenarioName()
    {
        return getCurrentScenarioState().scenarioName;
    }

    @Given("(?i)^ScenarioNameAndLine$")
    public static String getScenarionNameAndLine()
    {
        return getCurrentScenarioState().scenarioNameAndLine;
    }
}

