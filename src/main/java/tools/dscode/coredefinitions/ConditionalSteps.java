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
import tools.dscode.common.annotations.NoLogging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.common.annotations.DefinitionFlag.SKIP_CHILDREN;
import static tools.dscode.common.util.DebugUtils.printDebug;


public class ConditionalSteps extends CoreSteps {

    @Given("^((?:IF:|ELSE:|ELSE-IF:|THEN:).*)$")
    public static void runConditional(String inputString) {
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

        System.out.println("@@inputString1: "    + inputString);
        inputString = quoteThenElseSegments(inputString);
        System.out.println("@@inputString2: "    + inputString);

        String evaluatedString = currentStep.evalWithStepMaps(inputString);
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
            modifiedStep.addConditionalStates(StepData.ConditionalStates.TRUE);
            currentStep.attachedSteps.add(modifiedStep);
        }
    }


    static Pattern p = Pattern.compile(
            "\\b(THEN:|ELSE:)\\s*(.*?)\\s*(?=\\b(IF:|ELSE:|ELSE-IF:|THEN:)|$)"
    );

    public static String quoteThenElseSegments(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

       Matcher m = p.matcher(input);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String token = m.group(1);
            String content = m.group(2).trim();

            // Escape internal quote characters
            content = content.replace("\"", "\\\"");

            // Important: quote the replacement using Matcher.quoteReplacement
            String replacement = token + " \"" + content + "\" ";

            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }

        m.appendTail(sb);
        return sb.toString();
    }

}
