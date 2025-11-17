package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.treeparsing.DictionaryA;


public class DynamicSteps  extends CoreSteps {
    @Given("^,(.*)$")
    public void executeDynamicStep(String stepText) {
        DictionaryA dict = new DictionaryA();
        dict.parse(stepText);
    }
}
