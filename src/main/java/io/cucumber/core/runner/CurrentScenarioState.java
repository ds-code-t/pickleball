package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;

import java.util.List;

import static tools.dscode.common.util.Reflect.getProperty;

public class CurrentScenarioState {

    public final TestCase testCase;
    public final Pickle pickle;
    List<StepExtension> stepExtensions;

    public CurrentScenarioState(TestCase testCase) {
        this.testCase = testCase;
        this.pickle = (Pickle) getProperty(testCase, "pickle");
        this.stepExtensions = (List<StepExtension>) getProperty(testCase, "stepExtensions");
    }

    public void runStepExtensions() {
        System.out.println("@@runStepExtensions: " + this.stepExtensions.getFirst().pickleStepTestStep.getStep().getText());
        this.stepExtensions.getFirst().run();
    }


}
