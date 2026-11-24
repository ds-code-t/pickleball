package tools.dscode.engine.jserror;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import org.openqa.selenium.chrome.ChromeDriver;

import static io.cucumber.core.runner.GlobalState.getRunningStep;

/** E3 fixture glue: the test's BiDi Chrome opens a page, then a step makes the page throw. The test quits the driver. */
public class JsErrorPageGlue {
    public static ChromeDriver driver;
    public static String url;

    @Given("the error page is open")
    public void open() {
        CurrentScenarioState.registerScenarioObject("CHROME", driver);
        getRunningStep().webDriverUsed = driver;
        driver.get(url);
    }

    @When("the page throws {string}")
    public void pageThrows(String message) throws InterruptedException {
        getRunningStep().webDriverUsed = driver;
        driver.executeScript("setTimeout(function(){ throw new Error(arguments[0]); }.bind(null, arguments[0]), 0);", message);
        Thread.sleep(100);
    }
}
