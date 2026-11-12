package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.preparsing.LineData;
import tools.dscode.common.treeparsing.preparsing.ParsedLine;


import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.treeparsing.DefinitionContext.getNodeDictionary;


public class DynamicSteps  extends CoreSteps {


    @Given("^((?:IF:|ELSE:|ELSE-IF:).*)$")
    public void executeDynamicStepBlockConditionals(String stepText) {
        executeDynamicStep(stepText);
    }

    @Given("^,(?s)(.*)$")
    public void executeDynamicStep(String stepText) {
        tools.dscode.engine.runtime.SharedStepRun.executeDynamic(getRunningStep());
    }
}
