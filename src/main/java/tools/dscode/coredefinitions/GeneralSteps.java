package tools.dscode.coredefinitions;


import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.DefinitionFlags;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.reporting.logging.CleanupTrace;
import tools.dscode.common.reporting.logging.Log;
import tools.dscode.common.exceptions.SoftRuntimeException;
import tools.dscode.common.reporting.logging.reportportal.ReportPortalBridge;

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
import static tools.dscode.common.annotations.DefinitionFlag._DEBUG_LOGGING;
import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;


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
    public static void rootStep() throws Exception {
        getCurrentScenarioState().startScenarioRun();
    }


    @BeforeAll
    public static void beforeAll() {
        lifecycle.fire(Phase.BEFORE_CUCUMBER_RUN);
    }

    @AfterAll
    public static void afterAll() {
        CleanupTrace.print("[AfterAll] START");

        Throwable failure = null;

        CleanupTrace.print("[AfterAll] START: lifecycle.fire(AFTER_CUCUMBER_RUN)");
        try {
            lifecycle.fire(Phase.AFTER_CUCUMBER_RUN);
            CleanupTrace.print("[AfterAll] END: lifecycle.fire(AFTER_CUCUMBER_RUN)");
        } catch (Throwable t) {
            CleanupTrace.printThrowable("[AfterAll] THROWABLE: lifecycle.fire(AFTER_CUCUMBER_RUN)", t);
            failure = rememberFailure(failure, t);
        }

        CleanupTrace.print("[AfterAll] START: Log.global().closeAll()");
        try {
            Log.global().closeAll();
            CleanupTrace.print("[AfterAll] END: Log.global().closeAll()");
        } catch (Throwable t) {
            CleanupTrace.printThrowable("[AfterAll] THROWABLE: Log.global().closeAll()", t);
            failure = rememberFailure(failure, t);
        }

        String launchStatus = failure == null ? "PASSED" : "FAILED";

        CleanupTrace.print("[AfterAll] START: ReportPortalBridge.finishLaunch(" + launchStatus + ")");
        try {
            ReportPortalBridge.finishLaunch(launchStatus);
            CleanupTrace.print("[AfterAll] END: ReportPortalBridge.finishLaunch(" + launchStatus + ")");
        } catch (Throwable t) {
            CleanupTrace.printThrowable("[AfterAll] THROWABLE: ReportPortalBridge.finishLaunch(" + launchStatus + ")", t);
            failure = rememberFailure(failure, t);
        }

        CleanupTrace.print("[AfterAll] START: pickleballLog.stop()");
        try {
            pickleballLog.stop();
            CleanupTrace.print("[AfterAll] END: pickleballLog.stop()");
        } catch (Throwable t) {
            CleanupTrace.printThrowable("[AfterAll] THROWABLE: pickleballLog.stop()", t);
            failure = rememberFailure(failure, t);
        }

        if (failure != null) {
            CleanupTrace.print("[AfterAll] THROWING accumulated failure");
            throw new RuntimeException(failure);
        }

        CleanupTrace.print("[AfterAll] COMPLETE");
    }


    private static Throwable rememberFailure(Throwable primary, Throwable next) {
        if (next == null) return primary;
        if (primary == null) return next;
        if (primary != next) primary.addSuppressed(next);
        return primary;
    }

    @Given("^" + INFO_STEP + "(.*)$")
    public static void infoStep(String message) {
        logInfo(message);
    }

    @Given("^" + HARD_ERROR_STEP + "(.*)$")
    public static void hardFailStep(String message) {
        throw new RuntimeException(message);
    }

    @Given("^" + SOFT_ERROR_STEP + "(.*)$")
    public static void softFailStep(String message) {
        throw new SoftRuntimeException(message);
    }

    @Given("^SKIPPING: (.*)$")
    public static void skippedStep(String message) {
        logInfo("Skipping step: " + message);
    }


    @Given("^print(.*)$")
    public static void printVal(String message) {
        logInfo("PRINT: " + message);
    }

    @Given("^RETRY:.*$")
    public static void retryStep() {
// placeholder for retry
    }


    @DefinitionFlags(_DEBUG_LOGGING)
    @Given(NEXT_SIBLING_STEP)
    public static void NEXT_SIBLING_STEP() {

    }

    @Given("(?i)^ScenarioName$")
    public static String getScenarioName() {
        return getCurrentScenarioState().scenarioName;
    }

    @Given("(?i)^ScenarioNameAndLine$")
    public static String getScenarionNameAndLine() {
        return getCurrentScenarioState().scenarioNameAndLine;
    }
}

