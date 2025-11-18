package tools.dscode.coredefinitions;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.When;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chromium.ChromiumDriver;

import static io.cucumber.core.runner.CurrentScenarioState.getScenarioObject;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.coredefinitions.GeneralSteps.getBrowser;

public class NavigationSteps {

    @When("set {returnStepParameter}")
    public void setBrowser(String config) {
    }
}