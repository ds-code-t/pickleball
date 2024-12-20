package io.pickleball.cacheandstate;

import io.cucumber.core.runner.Runner;
import io.cucumber.core.runner.TestCase;

import java.util.Stack;

import static io.pickleball.cacheandstate.GlobalCache.getState;

public class PrimaryScenarioData {

    private ScenarioContext primaryScenario;
    private ScenarioContext currentScenario;

    private Stack<ScenarioContext> scenarioStack = new Stack<>();

    private final Runner runner;
    private final ScenarioContext scenarioContext;



    public static void startEvent() {
        getCurrentScenario().currentStep.sendStartEvent();
    }

    public static void endEvent() {
        getCurrentScenario().currentStep.sendEndEvent();
    }


    public static StepContext getCurrentStep() {
        return getCurrentScenario().currentStep;
    }

    public static void setCurrentStep(StepContext currentStep) {
        getCurrentScenario().currentStep = currentStep;
    }

    public PrimaryScenarioData(Runner runner, TestCase testCase) {
        this.runner = runner;
        this.scenarioContext = testCase.scenarioContext;
        this.currentScenario  = testCase.scenarioContext;
    }


//    public PrimaryScenarioData(Runner runner, TestCase testCase) {
//
//    }

    public static ScenarioContext getPrimaryScenario() {
        return getState().primaryScenario;
    }

    public static void setPrimaryScenario(ScenarioContext primaryScenario) {
        getState().primaryScenario = primaryScenario;
    }

    public static ScenarioContext popCurrentScenario() {
        return getState().scenarioStack.pop();
    }

    public static ScenarioContext getCurrentScenario() {
        return getState().scenarioStack.peek();
    }

    public static void setCurrentScenario(ScenarioContext currentScenario) {
        getState().scenarioStack.add(currentScenario);
    }

    public static Runner getRunner() {
        return  getState().runner;
    }
}
