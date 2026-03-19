package tools.dscode.coredefinitions;

import io.cucumber.java.en.When;
import org.openqa.selenium.WebDriver;

import static io.cucumber.core.runner.GlobalState.getRunningParsingMap;
import static tools.dscode.common.reporting.logging.LogForwarder.stepInfo;

public class NavigationSteps {


    @When("^navigate to: (.*)$")
    public void i_navigate_to(String text) {
        text = getRunningParsingMap().getCaseInsensitiveAndResolve("configs." + text);
        stepInfo("Attempting to navigate to: " + text + "");
        WebDriver driver = BrowserSteps.getDefaultDriver();
        driver.get(text);
    }
}