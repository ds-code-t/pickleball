package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import org.openqa.selenium.chromium.ChromiumDriver;

import java.util.List;

import static io.cucumber.core.runner.CurrentScenarioState.endScenario;
import static io.cucumber.core.runner.CurrentScenarioState.failScenario;
import static io.cucumber.core.runner.CurrentScenarioState.softFailScenario;


public class MessageAndLoggingSteps {

    @Given("^END SCENARIO$")
    public void endScenarioStep() {
        System.out.println("Manually ending Scenario");
        endScenario();
    }

    @Given("^SOFT FAIL SCENARIO( \".*\")$")
    public void softFailScenarioStep(String failMessage) {
        System.out.println("Manually soft failing Scenario");
        if(failMessage != null)
            System.out.println(failMessage);
        softFailScenario(failMessage);
    }

    @Given("^FAIL SCENARIO( \".*\")$")
    public void failScenarioStep(String failMessage) {
        System.out.println("Manually hard failing Scenario");
        if(failMessage != null)
            System.out.println(failMessage);
        failScenario(failMessage);
    }

}
