package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import tools.dscode.extensions.StepExtension;
import tools.dscode.extensions.StepRelationships;

import static tools.dscode.state.ScenarioState.getScenarioState;

public class ConditionalSteps {

    @Given("^((?:IF:|ELSE:|ELSE-IF:|THEN:).*)$")
    public static void runConditional(String inputString) {
        StepExtension currentStep = getScenarioState().getCurrentStep();
        if (inputString.startsWith("ELSE")) {
            if (!currentStep.getPreviousSibling().getConditionalStates()
                    .contains(StepRelationships.ConditionalStates.FALSE)) {
                currentStep.addConditionalStates(StepRelationships.ConditionalStates.SKIP_CHILDREN);
                return;
            } else {
                if (inputString.equals("ELSE:")) {
                    currentStep.addConditionalStates(StepRelationships.ConditionalStates.TRUE);
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
            currentStep.addConditionalStates(StepRelationships.ConditionalStates.FALSE);
            currentStep.addConditionalStates(StepRelationships.ConditionalStates.SKIP_CHILDREN);
            System.out.println("Conditional returned false.  Will skip child steps.");
        } else if (evaluatedString.equals("true")) {
            currentStep.addConditionalStates(StepRelationships.ConditionalStates.TRUE);
            System.out.println("Conditional returned true.  Will run child steps.");
        } else {
            currentStep.addConditionalStates(StepRelationships.ConditionalStates.SKIP_CHILDREN);
            StepExtension modifiedStep = currentStep.modifyStep(evaluatedString);
            System.out.println("@@modifiedStep: " + modifiedStep);
            modifiedStep.addConditionalStates(StepRelationships.ConditionalStates.TRUE);
            currentStep.insertNextSibling(modifiedStep);
        }
    }
    //
    // @Given("false")
    // public static void falseStep() {
    // getCurrentStep().addConditionalStates(StepRelationships.ConditionalStates.SKIP_CHILDREN);
    // System.out.println("Conditional returned false");
    // }
    //
    // @Given("true")
    // public static void trueStep() {
    // System.out.println("Conditional returned true");
    // }

}
