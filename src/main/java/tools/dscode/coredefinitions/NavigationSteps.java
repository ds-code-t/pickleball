package tools.dscode.coredefinitions;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.When;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chromium.ChromiumDriver;

import static io.cucumber.core.runner.CurrentScenarioState.getScenarioObject;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.common.util.DebugUtils.printDebug;
import static tools.dscode.coredefinitions.GeneralSteps.getBrowser;

public class NavigationSteps {

    @When("set {returnStepParameter}")
    public void setObject(Object object, String config) {

    }

    @When("^navigate to: (.*)$")
    public void i_navigate_to(String text) {
        text = "configs." + text;
        WebDriver driver = (WebDriver) getScenarioObject("browser");
        StepExtension stepExtension = getCurrentScenarioState().getCurrentStep();
        driver.get((String) stepExtension.getStepParsingMap().getAndResolve(text));
    }
}