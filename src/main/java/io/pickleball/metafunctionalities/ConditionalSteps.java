package io.pickleball.metafunctionalities;

import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.pickleball.annotations.NoEventEmission;
import io.pickleball.cacheandstate.BaseContext;
import io.pickleball.cacheandstate.ScenarioContext;
import io.pickleball.customtypes.DynamicStep;
import io.pickleball.exceptions.PickleballException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.cucumber.core.gherkin.messages.GherkinMessagesStep.*;
import static io.pickleball.cacheandstate.PrimaryScenarioData.*;
import static io.pickleball.configs.Constants.orSubstitue;
import static io.pickleball.cucumberutilities.CucumberObjectFactory.createPickleStepTestStep;
import static io.pickleball.valueresolution.BooleanResolver.resolveObjectToBoolean;

public class ConditionalSteps {
    @NoEventEmission
    @Given("^@MetaStepDefinition$")
    public static void MetaStepDefinition() {
        // Placeholder for when steps don't have text
    }

    //    @NoEventEmission
    @Given("RUN CHILD STEPS")
    public static void runChildren() {
        getCurrentStep().setForceRunNestedSteps(true);
    }


    @NoEventEmission
    @Given("^((?!IF:).* THEN:.*)$")
    public static Object runConditionalThen(String inputString) {
        return runConditional(inputString);
    }

    @NoEventEmission
    @Given("^IF:(.*)$")
    public static Object runConditional(String inputString) {
        ScenarioContext currentScenario = getCurrentScenario();
        String ifelseString = inputString.contains("THEN:") ? inputString : inputString + " THEN: @MetaStepDefinition ";
        ifelseString = (ifelseString.replaceFirst("(.*) ELSE:", "$1 ELSE-IF: true THEN: "))
                .replaceAll(" ELSE-IF:| THEN:", "\u0001");
        String[] pairs = ifelseString.split("\u0001");
        for (int i = 0; i < pairs.length - 1; i += 2) {
            String condition = "{" + pairs[i].trim() + "}";
            if (resolveObjectToBoolean(currentScenario.replaceAndEval(condition))) {
                System.out.println("Executed: " + pairs[i + 1]);
                getCurrentStep().setRunNestedSteps(true);
                return DynamicStep.runStep(pairs[i + 1]);
            }
        }
        getCurrentStep().setRunNestedSteps(false);
        return null;
    }





    @When("@Terminate:(.*),,,,(.*)")
    public void terminate(String terminationType, String description) {
        if (terminationType.equals(FAIL_SCENARIO) || terminationType.equals(FAIL_TEST))
            throw new PickleballException(description);
        if (terminationType.equals(END_SCENARIO))
            getCurrentScenario().forceComplete();
        else if (terminationType.equals(END_TEST)) {
            getCurrentScenario().forceComplete();
            getPrimaryScenario().forceComplete();
        }
    }


}
