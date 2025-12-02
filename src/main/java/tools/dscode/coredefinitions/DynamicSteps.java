package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.treeparsing.preparsing.ParsedLine;


import static tools.dscode.common.treeparsing.DefinitionContext.getNodeDictionary;


public class DynamicSteps  extends CoreSteps {
    @Given("^,(.*)$")
    public void executeDynamicStep(String stepText) {
        new ParsedLine(stepText);
        getNodeDictionary().parse(stepText);
    }
}
