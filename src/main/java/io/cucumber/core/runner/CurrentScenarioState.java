package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;

import static tools.dscode.common.util.Reflect.getProperty;

public class CurrentScenarioState {

    public final TestCase testCase;
    public final Pickle pickle;

    public CurrentScenarioState(TestCase testCase)
    {
        this.testCase = testCase;
        this.pickle = (Pickle) getProperty(testCase,"pickle");
    }

}
