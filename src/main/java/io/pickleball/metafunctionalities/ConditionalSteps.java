package io.pickleball.metafunctionalities;

import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.java.en.Given;
import io.pickleball.customtypes.DynamicStep;

import static io.pickleball.cacheandstate.PrimaryScenarioData.getCurrentStep;
import static io.pickleball.cucumberutilities.CucumberObjectFactory.createPickleStepTestStep;

public class ConditionalSteps {

//    @Metastep
    @Given("@IF: {dynamicStep}")
    public void A(DynamicStep dynamicStep) {
        System.out.println("@@ dynamicStep: "+ dynamicStep);
        PickleStepTestStep pickleStepTestStep = createPickleStepTestStep(dynamicStep.getStepText());
        getCurrentStep().addStepsToStack(pickleStepTestStep);
    }



}
