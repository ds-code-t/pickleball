package tools.dscode.coredefinitions;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.preparsing.LineData;
import tools.dscode.common.treeparsing.preparsing.ParsedLine;


import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.treeparsing.DefinitionContext.getNodeDictionary;


public class DynamicSteps  extends CoreSteps {
    @Given("^,(.*)$")
    public void executeDynamicStep(String stepText) {
        StepExtension currentStep = getRunningStep();
        currentStep.insertStepsByString(currentStep.lineData.lineComponents);
        currentStep.lineData.runPhrases();
    }
}
