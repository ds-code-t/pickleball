package tools.ds.modkit.coredefinitions;

import io.cucumber.java.en.Given;


public class FlagSteps {

    public static final String ALWAYS_RUN = "ALWAYS RUN";
    public static final String RUN_IF_SCENARIO_PASSING = "RUN IF SCENARIO PASSING";
    public static final String RUN_IF_SCENARIO_FAILED = "RUN IF SCENARIO FAILED";
    public static final String RUN_IF_SCENARIO_HARD_FAILED = "RUN IF SCENARIO HARD FAILED";
    public static final String RUN_IF_SCENARIO_SOFT_FAILED = "RUN IF SCENARIO SOFT FAILED";
    public static final String IGNORE_FAILURES = "IGNORE FAILURES";
    public static final String LOG_FAILURES_BUT_CONTINUE_SCENARIO = "LOG FAILURES BUT CONTINUE SCENARIO";
    public static final String RUN_IF_SCENARIO_FINISHED = "RUN IF SCENARIO FINISHED";
    public static final String AND_SCENARIO_COMPLETE = "( AND SCENARIO FINISHED)?";
    public static final String AND_IGNORE_FAILURES = "( AND IGNORE FAILURES)?";


    @Given("^" + ALWAYS_RUN+ AND_IGNORE_FAILURES +"$")
    public static void flagStep_AlwaysRun() {

    }

    @Given("^" + RUN_IF_SCENARIO_PASSING + AND_SCENARIO_COMPLETE + AND_IGNORE_FAILURES +"$")
    public static void flagStep_RunIfPassed() {

    }

    @Given(RUN_IF_SCENARIO_FAILED)
    public static void flagStep_RunOnFail() {

    }

    @Given(RUN_IF_SCENARIO_HARD_FAILED)
    public static void flagStep_RunOnHardFail() {

    }

    @Given(RUN_IF_SCENARIO_SOFT_FAILED)
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
