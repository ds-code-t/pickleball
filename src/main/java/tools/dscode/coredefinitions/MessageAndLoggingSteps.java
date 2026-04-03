package tools.dscode.coredefinitions;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.java.en.Given;

import static io.cucumber.core.runner.CurrentScenarioState.endTest;
import static io.cucumber.core.runner.CurrentScenarioState.failScenario;
import static io.cucumber.core.runner.CurrentScenarioState.softFailScenario;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.common.reporting.logging.LogForwarder.stepFail;
import static tools.dscode.common.reporting.logging.LogForwarder.stepInfo;


public class MessageAndLoggingSteps {

    @Given("^END SCENARIO$")
    public void endScenarioStep() {
        stepInfo("Manually ending Current Scenario");
        getCurrentScenarioState().endCurrentScenario = true;
    }

    @Given("^END Test")
    public void manuallyEndTest() {
        stepInfo("Manually ending Test");
        endTest();
    }

    @Given("^SOFT FAIL SCENARIO( \".*\")$")
    public void softFailScenarioStep(String failMessage) {
        stepFail("Manually soft failing Scenario");
        if(failMessage != null)
            stepFail(failMessage);
        softFailScenario(failMessage);
    }

    @Given("^FAIL SCENARIO( \".*\")$")
    public void failScenarioStep(String failMessage) {
        stepFail("Manually hard failing Scenario");
        if(failMessage != null)
            stepFail(failMessage);
        failScenario(failMessage);
    }

}
