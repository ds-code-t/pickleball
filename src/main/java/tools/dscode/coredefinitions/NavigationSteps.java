package tools.dscode.coredefinitions;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.java.en.When;
import org.openqa.selenium.WebDriver;

import static io.cucumber.core.runner.GlobalState.getRunningParsingMap;
import static io.cucumber.core.runner.GlobalState.getRunningStep;

public class NavigationSteps {

    @When("set {returnStepParameter}")
    public void setObject(Object object, String config) {

    }

    @When("^navigate to: (.*)$")
    public void i_navigate_to(String text) {
        text = getRunningParsingMap().getCaseInsensitiveAndResolve("configs." + text);
        System.out.println("Attempting to navigate to: " + text + "");
        WebDriver driver = GeneralSteps.getDefaultDriver();
        driver.get(text);
    }
}