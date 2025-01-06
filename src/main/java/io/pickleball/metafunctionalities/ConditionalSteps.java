package io.pickleball.metafunctionalities;

import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.pickleball.annotations.NoEventEmission;
import io.pickleball.cacheandstate.BaseContext;
import io.pickleball.cacheandstate.ScenarioContext;
import io.pickleball.customtypes.DynamicStep;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.pickleball.cacheandstate.PrimaryScenarioData.*;
import static io.pickleball.configs.Constants.orSubstitue;
import static io.pickleball.cucumberutilities.CucumberObjectFactory.createPickleStepTestStep;
import static io.pickleball.valueresolution.BooleanResolver.resolveObjectToBoolean;

public class ConditionalSteps {

    //    @NoEventEmission
    @Given("^IF: (.*)$")
    public static void ifElseStep(String string) {
        String ifelseString = (string.replaceFirst("(.*) ELSE: ", "$1 ELSE-IF: true THEN: "))
                .replaceAll("ELSE-IF:|THEN:", "\u0001");
        System.out.println("@@ifelseString: " + ifelseString);
        String[] pairs = ifelseString.split("\u0001");
        ScenarioContext currentScenario = getCurrentScenario();
// Iterate through pairs
        for (int i = 0; i < pairs.length - 1; i += 2) {
            String condition = "{" + pairs[i].trim() + "}";
            System.out.println("@@condition: " + condition);
            System.out.println("@@value: " + pairs[i + 1]);
            System.out.println("@@currentScenario.replaceAndEval(condition): " + currentScenario.replaceAndEval(condition));
            System.out.println("@@resolveObjectToBoolean: " + resolveObjectToBoolean(currentScenario.replaceAndEval(condition)));
            if (resolveObjectToBoolean(currentScenario.replaceAndEval(condition))) {
                DynamicStep.runStep(pairs[i + 1]);
                System.out.println("Executed: " + pairs[i + 1]);
                return;
            }
        }

    }


    @NoEventEmission
    @Given("{ifElseConditional}")
    public static void ifElseStep(DynamicStep dynamicStep) {
        System.out.println("@@elseStep!!");
        System.out.println("@@dynamicStep: " + dynamicStep.getStepText());
        System.out.println("@@getAdditionalData: " + dynamicStep.getAdditionalData());
        PickleStepTestStep pickleStepTestStep = createPickleStepTestStep(dynamicStep.getStepText());
        getCurrentStep().addPostStackSteps(pickleStepTestStep);
    }

    @Given(orSubstitue + "@RUN:" + orSubstitue + "{metaParameter}{ifConditional}")
    public static void RunSteps(String metaParameter, Boolean bool) {
        System.out.println("@@RunSteps " + metaParameter);
        System.out.println("@@bool " + bool);
    }

    @Given(orSubstitue + "@POST-SCENARIO-STEPS:{ifConditional}")
    public static void postRunSteps(Boolean bool) {
        System.out.println("@@@POST-SCENARIO: " + bool);
        ScenarioContext currentScenario = getCurrentScenario();
        currentScenario.getTestCaseState().completeScenario();
        getCurrentScenario().addStatusFlag(BaseContext.RunCondition.RUN_ON_END);
    }


    @When("baa")
    public void abiExecuteASteplzz() {
        System.out.println("baa DEBUG");
    }

    @When("^bc (.*) a steplzz")
    public void biExecuteASteplzz(String arg) {
        System.out.println("DEBUG: bc: " + arg);
    }

    @Given("bbb (.*)")
    public static void aRunSteps(String a) {
        System.out.println("RunSteps " + a);
    }


//    @Given("@{ifElseConditional}")
//    public static void ifElseStep(DynamicStep dynamicStep) {
//        System.out.println("@@elseStep!!");
//        System.out.println("@@dynamicStep: " + dynamicStep.getStepText());
//        System.out.println("@@getAdditionalData: " + dynamicStep.getAdditionalData());
//        PickleStepTestStep pickleStepTestStep = createPickleStepTestStep(dynamicStep.getStepText());
//        getCurrentStep().addPostStackSteps(pickleStepTestStep);
//    }


//␄@RUN:  NEXT STEPS ␄@IF:false␄
//    @Given(orSubstitue + "@RUN:"  + "[^"+orSubstitue+"]*{ifConditional}"  )
//    @Given(orSubstitue + "@RUN:.*␄@IF:.*␄"   )
//    @Given("␄@RUN:(.*)␄@IF:.*␄"    )
//    @Given("@␄@RUN:(.*)@IF(?:[^␄]*)␄"    )
//    @Given("@␄@RUN: NEXT STEPS  @IFaa␄"    )
//    public static void RunSteps(  String  steps )   {
//        System.out.println("@@RunSteps " + steps);
//
//    }

//    @Given("^"+orSubstitue + "@RUN:(.*)$"  )
//    public static void RunSteps( String steps)   {
//        System.out.println("@@RunSteps " + steps);
//
//    }

//
//    @Given("@ALWAYS-RUN:" + orSubstitue  + "([^@]*)" + "{runCondition}" + orSubstitue )
//    public static void alwaysRunSteps( String runtep) {
//    }


//    @Given("@IF:{whiteSpace}" + orSubstitue + "{booleanEvaluator}@THEN:{dynamicStep}@ELSE:{dynamicStep}" + orSubstitue)
//    public static void IFStep(String whiteSpace, Boolean evalExpression, DynamicStep dynamicStep1, DynamicStep dynamicStep2) {
//        System.out.println("@@ whitespace: " + whiteSpace);
//        System.out.println("@@ evalString: " + evalExpression);
//        System.out.println("@@ evalString: " + evalExpression);
//        System.out.println("@@ dynamicStep1: " + dynamicStep1);
//        System.out.println("@@ dynamicStep2: " + dynamicStep2);
//        if (evalExpression) {
//            PickleStepTestStep pickleStepTestStep = createPickleStepTestStep(dynamicStep1.getStepText());
//            getCurrentStep().addStepsToStack(pickleStepTestStep);
//        } else {
//            PickleStepTestStep pickleStepTestStep = createPickleStepTestStep( dynamicStep2.getStepText());
//            getCurrentStep().addStepsToStack(pickleStepTestStep);
//        }

}
