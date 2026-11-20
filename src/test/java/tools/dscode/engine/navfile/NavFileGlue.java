package tools.dscode.engine.navfile;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.java.en.Given;
import org.openqa.selenium.chrome.ChromeDriver;

/** Registers the test's already-open Chrome. The navigate step loads the page. The test quits the driver. */
public class NavFileGlue {
    public static ChromeDriver driver;

    @Given("the chrome page is ready")
    public void ready() {
        CurrentScenarioState.registerScenarioObject("CHROME", driver);
    }
}
