package tools.dscode.engine.maskquiet;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.java.en.Given;
import org.openqa.selenium.chrome.ChromeDriver;

/** Registers the test's already-open Chrome and arms the covering mask. The test quits the driver. */
public class MaskPageGlue {
    public static ChromeDriver driver;
    public static String url;

    @Given("a covering mask is armed")
    public void arm() {
        CurrentScenarioState.registerScenarioObject("CHROME", driver);
        driver.get(url);
        driver.executeScript("""
                window.armedAt = Date.now();
                window.early = false;
                window.clickedAt = 0;
                document.title = 'Ready';
                setTimeout(function(){
                  var mask = document.getElementById('mask');
                  if (mask) mask.remove();
                }, 900);
                """);
    }
}
