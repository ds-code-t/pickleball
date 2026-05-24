package tools.dscode.coredefinitions;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.java.en.Given;

import static io.cucumber.core.runner.CurrentScenarioState.endTest;
import static io.cucumber.core.runner.CurrentScenarioState.failScenario;
import static io.cucumber.core.runner.CurrentScenarioState.softFailScenario;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;


public class MessageAndLoggingSteps {

    @Given("^END SCENARIO$")
    public void endScenarioStep() {
        logInfo("Manually ending Current Scenario");
        getCurrentScenarioState().endCurrentScenario = true;
    }

    @Given("^END TEST")
    public void manuallyEndTest() {
        logInfo("Manually ending Test");
        endTest();
    }

    @Given("^SOFT FAIL SCENARIO( \".*\")$")
    public void softFailScenarioStep(String failMessage) {
        logInfo("Manually soft failing Scenario");
        if(failMessage != null)
            logInfo(failMessage);
        softFailScenario(failMessage);
    }

    @Given("^FAIL SCENARIO( \".*\")$")
    public void failScenarioStep(String failMessage) {
        logInfo("Manually hard failing Scenario");
        if(failMessage != null)
            logInfo(failMessage);
        failScenario(failMessage);
    }

}
