package tools.dscode.coredefinitions;

//import io.cucumber.java.en.Given;
//import tools.dscode.common.CoreSteps;
//import tools.dscode.extensions.StepExtension;
//import tools.dscode.extensions.StepData;
//
//import static io.cucumber.core.runner.ScenarioState.getScenarioState;

import io.cucumber.core.runner.StepData;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.common.annotations.DefinitionFlag.SKIP_CHILDREN;


public class ConditionalSteps extends CoreSteps {

    @Given("^((?:IF:|ELSE:|ELSE-IF:|THEN:).*)$")
    public static void runConditional(String inputString) {
        System.out.println("@@runConditional: " + inputString);
        StepExtension currentStep = getCurrentScenarioState().getCurrentStep();
        if (inputString.startsWith("ELSE")) {
            if (!currentStep.previousSibling.getConditionalStates()
                    .contains(StepData.ConditionalStates.FALSE)) {
                currentStep.addDefinitionFlag(SKIP_CHILDREN);
                return;
            } else {
                if (inputString.equals("ELSE:")) {
                    currentStep.addConditionalStates(StepData.ConditionalStates.TRUE);
                    return;
                }

                if (inputString.startsWith("ELSE:"))
                    inputString = inputString.replaceFirst("ELSE:", "IF: true THEN: ");
                else
                    inputString = inputString.replaceFirst("ELSE-IF:", "IF: ");
            }
        }
        System.out.println("@@runConditional: " + inputString);
        String evaluatedString = currentStep.evalWithStepMaps(inputString);
        System.out.println("@@evaluatedString: " + evaluatedString);
        if (evaluatedString.equals("false")) {
            currentStep.addConditionalStates(StepData.ConditionalStates.FALSE);
            currentStep.addDefinitionFlag(SKIP_CHILDREN);
            System.out.println("Conditional returned false.  Will skip child steps.");
        } else if (evaluatedString.equals("true")) {
            currentStep.addConditionalStates(StepData.ConditionalStates.TRUE);
            System.out.println("Conditional returned true.  Will run child steps.");
        } else {
            currentStep.addDefinitionFlag(SKIP_CHILDREN);
            StepExtension modifiedStep = currentStep.modifyStepExtension(evaluatedString);
            System.out.println("@@modifiedStep: " + modifiedStep);
            modifiedStep.addConditionalStates(StepData.ConditionalStates.TRUE);
            currentStep.attachedSteps.add(modifiedStep);
        }
    }
    //
    // @Given("false")
    // public static void falseStep() {
    // getCurrentStep().addConditionalStates(StepData.ConditionalStates.SKIP_CHILDREN);
    // System.out.println("Conditional returned false");
    // }
    //
    // @Given("true")
    // public static void trueStep() {
    // System.out.println("Conditional returned true");
    // }

}
