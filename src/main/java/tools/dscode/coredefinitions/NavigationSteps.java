package tools.dscode.coredefinitions;

import io.cucumber.java.en.When;
import org.openqa.selenium.WebDriver;

import static tools.dscode.common.mappings.ParsingMap.configsRoot;
import static tools.dscode.common.mappings.ParsingMap.getFromRunningParsingMapCaseInsensitive;
import static tools.dscode.common.mappings.ParsingMap.getRunningParsingMap;
import static tools.dscode.common.reporting.logging.LogForwarder.stepInfo;

public class NavigationSteps {


    @When("^navigate to: (.*)$")
    public void i_navigate_to(String text) {
        Object obj = getFromRunningParsingMapCaseInsensitive(configsRoot + "." + text);
        if(obj instanceof String address) {
            stepInfo("Attempting to navigate to: " + address + "");
            WebDriver driver = BrowserSteps.getDefaultDriver();
            driver.get(address);
        }
        else {
            throw new RuntimeException("failed to navigate to: '" + text + "' , URL resolved to '" +  obj + "'"   );
        }
    }
}