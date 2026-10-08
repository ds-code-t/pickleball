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



    @Given("^" + ALWAYS_RUN + "(?: AND IGNORE FAILURES|\\s*:.*)?$")
    public static void flagStep_AlwaysRun() {

    }

    @Given("^" + RUN_IF_SCENARIO_PASSING + "(?:(?: AND SCENARIO FINISHED)?(?: AND IGNORE FAILURES)?|\\s*:.*)$")
    public static void flagStep_RunIfPassed() {

    }

    @Given("^" + RUN_IF_SCENARIO_FAILED + "(?:\\s*:.*)?$")
    public static void flagStep_RunOnFail() {

    }

    @Given("^" + RUN_IF_SCENARIO_HARD_FAILED + "(?:\\s*:.*)?$")
    public static void flagStep_RunOnHardFail() {

    }

    @Given("^" + RUN_IF_SCENARIO_SOFT_FAILED + "(?:\\s*:.*)?$")
    public static void flagStep_RunOnSoftFail() {

    }

    @Given(IGNORE_FAILURES)
    public static void flagStep_IgnoreFailures() {

    }

    @Given(LOG_FAILURES_BUT_CONTINUE_SCENARIO)
    public static void flagStep_LogFailuresButContinue() {

    }

    @Given(RUN_IF_SCENARIO_FINISHED)
    public static void flagStep_RunIfFinished() {

    }

}
