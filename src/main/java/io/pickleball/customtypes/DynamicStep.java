package io.pickleball.customtypes;

import io.cucumber.core.runner.ExecutionMode;
import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.plugin.event.TestCase;
import io.pickleball.cacheandstate.ScenarioContext;

import static io.pickleball.cacheandstate.PrimaryScenarioData.getCurrentScenario;
import static io.pickleball.cucumberutilities.CucumberObjectFactory.createPickleStepTestStep;

public class DynamicStep {
    private final String stepText;

    public String getAdditionalData() {
        return additionalData;
    }

    private String additionalData;

    public DynamicStep(String stepText) {
        this.stepText = stepText;
    }

    public DynamicStep(String stepText, String additionalData) {
        this.stepText = stepText;
        this.additionalData = additionalData;
    }

    public static final String[] KEYWORDS = {
            "Given ", "When ", "Then ", "And ", "But ", "* "
    };

    public String getStepText() {
        return stepText;
    }

    public static String stripPrefixIfMatch(String input, String[] possiblePrefixes) {
        String strippedLeadingString = input.stripLeading();
        for (String prefix : possiblePrefixes) {
            if (strippedLeadingString.startsWith(prefix)) {
                return strippedLeadingString.substring(prefix.length());
            }
        }
        return input;
    }

    private static boolean isNoStepDefinitionFound(RuntimeException e) {
        return e.getMessage() != null &&
                e.getMessage().contains("No step definition found");
    }

    public static PickleStepTestStep stripAndCreatePickleStepTestStep(String originalStepText) {
        String stripped = stripPrefixIfMatch(originalStepText, KEYWORDS);
        try {
            return createPickleStepTestStep(stripped);
        } catch (RuntimeException e) {
            if (isNoStepDefinitionFound(e)) {
                return createPickleStepTestStep(originalStepText);
            } else {
                throw e;
            }
        }
    }

    public Object runStep() {
        PickleStepTestStep pickleStepTestStep = createPickleStepTestStep(stepText);
        ScenarioContext scenarioContext = getCurrentScenario();
        return pickleStepTestStep.run((TestCase) scenarioContext, scenarioContext.getRunner().bus, scenarioContext.getTestCaseState(), ExecutionMode.RUN);
    }

    public static Object runStep(String originalStepText) {
        PickleStepTestStep pickleStepTestStep = stripAndCreatePickleStepTestStep(originalStepText);
        ScenarioContext scenarioContext = getCurrentScenario();
        pickleStepTestStep.run((TestCase) scenarioContext, scenarioContext.getRunner().bus, scenarioContext.getTestCaseState(), ExecutionMode.RUN);
        return pickleStepTestStep.getLastExecutionReturnValue();
    }


    @Override
    public String toString() {
        return "MetaStep{stepText=" + stepText + '}';
    }
}
