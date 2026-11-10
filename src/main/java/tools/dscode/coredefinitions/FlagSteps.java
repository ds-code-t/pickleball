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

    }

    @Given("^RUN IF SCENARIO PASSING(?:(?: AND SCENARIO FINISHED)?(?: AND IGNORE FAILURES)?|\\s*:.*)$")
    public static void flagStep_RunIfPassed() {

    }

    @Given("^RUN IF SCENARIO FAILED(?:\\s*:.*)?$")
    public static void flagStep_RunOnFail() {

    }

    @Given("^RUN IF SCENARIO HARD FAILED(?:\\s*:.*)?$")
    public static void flagStep_RunOnHardFail() {

    }

    @Given("^RUN IF SCENARIO SOFT FAILED(?:\\s*:.*)?$")
    public static void flagStep_RunOnSoftFail() {

    }

    @Given("IGNORE FAILURES")
    public static void flagStep_IgnoreFailures() {

    }

    @Given("LOG FAILURES BUT CONTINUE SCENARIO")
    public static void flagStep_LogFailuresButContinue() {

    }

    @Given("RUN IF SCENARIO FINISHED")
    public static void flagStep_RunIfFinished() {

    }

}
