package tools.dscode.coredefinitions;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.java.en.When;
import org.openqa.selenium.WebDriver;

import static io.cucumber.core.runner.CurrentScenarioState.getScenarioObject;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.coredefinitions.GeneralSteps.getBrowser;

public class NavigationSteps {

    @When("set {returnStepParameter}")
    public void setObject(Object object, String config) {

    }

    @When("^navigate to: (.*)$")
    public void i_navigate_to(String text) {
        text = "configs." + text;
        WebDriver driver = getBrowser();
        StepExtension stepExtension = getRunningStep();
        driver.get((String) stepExtension.getStepParsingMap().getAndResolve(text));
    }
}