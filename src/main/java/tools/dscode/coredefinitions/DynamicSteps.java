package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import org.openqa.selenium.chromium.ChromiumDriver;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.treeparsing.DictionaryA;

import java.time.Duration;
import java.util.List;


public class DynamicSteps  extends CoreSteps {
    @Given("^,(.*)$")
    public void executeDynamicStep(String stepText) {
        DictionaryA dict = new DictionaryA();
        dict.parse(stepText);
    }
}
