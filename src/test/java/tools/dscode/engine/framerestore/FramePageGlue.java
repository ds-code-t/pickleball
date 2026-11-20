package tools.dscode.engine.framerestore;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.java.en.Given;
import org.openqa.selenium.chrome.ChromeDriver;

/** Registers the test's already-open Chrome and loads the frame page. The test quits the driver. */
public class FramePageGlue {
    public static ChromeDriver driver;
    public static String url;

    @Given("the frame page is open")
    public void open() {
        CurrentScenarioState.registerScenarioObject("CHROME", driver);
        driver.get(url);
    }
}
