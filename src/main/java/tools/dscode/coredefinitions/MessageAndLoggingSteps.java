package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import org.openqa.selenium.chromium.ChromiumDriver;

import java.util.List;

import static io.cucumber.core.runner.CurrentScenarioState.endScenario;
import static io.cucumber.core.runner.CurrentScenarioState.failScenario;
import static io.cucumber.core.runner.CurrentScenarioState.softFailScenario;
import static io.cucumber.core.runner.GlobalState.stepFail;
import static io.cucumber.core.runner.GlobalState.stepInfo;


public class MessageAndLoggingSteps {

    @Given("^END SCENARIO$")
    public void endScenarioStep() {
        stepInfo("Manually ending Scenario");
        endScenario();
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
