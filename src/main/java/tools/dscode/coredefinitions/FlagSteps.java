package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;

import static tools.dscode.common.GlobalConstants.ALWAYS_RUN;
import static tools.dscode.common.GlobalConstants.IGNORE_FAILURES;
import static tools.dscode.common.GlobalConstants.LOG_FAILURES_BUT_CONTINUE_SCENARIO;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_FINISHED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_HARD_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_PASSING;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_SOFT_FAILED;

public class FlagSteps  extends CoreSteps {



    @Given("^ALWAYS RUN(?: AND IGNORE FAILURES|\\s*:.*)?$")
    public static void flagStep_AlwaysRun() {
        applyRunningPhrase();
    }

    @Given("^RUN IF SCENARIO PASSING(?:(?: AND SCENARIO FINISHED)?(?: AND IGNORE FAILURES)?|\\s*:.*)$")
    public static void flagStep_RunIfPassed() {
        applyRunningPhrase();
    }

    @Given("^RUN IF SCENARIO FAILED(?:\\s*:.*)?$")
    public static void flagStep_RunOnFail() {
        applyRunningPhrase();
    }

    @Given("^RUN IF SCENARIO HARD FAILED(?:\\s*:.*)?$")
    public static void flagStep_RunOnHardFail() {
        applyRunningPhrase();
    }

    @Given("^RUN IF SCENARIO SOFT FAILED(?:\\s*:.*)?$")
    public static void flagStep_RunOnSoftFail() {
        applyRunningPhrase();
    }

    @Given("IGNORE FAILURES")
    public static void flagStep_IgnoreFailures() {
        applyRunningPhrase();
    }

    @Given("LOG FAILURES BUT CONTINUE SCENARIO")
    public static void flagStep_LogFailuresButContinue() {
        applyRunningPhrase();
    }

    @Given("RUN IF SCENARIO FINISHED")
    public static void flagStep_RunIfFinished() {
        applyRunningPhrase();
    }

    private static void applyRunningPhrase() {
        io.cucumber.core.runner.StepExtension step = io.cucumber.core.runner.GlobalState.getRunningStep();
        String text = step == null ? "" : step.getUnmodifiedText();
        tools.dscode.engine.runtime.StepFlags.applyFromPhrase(text);
    }

}
