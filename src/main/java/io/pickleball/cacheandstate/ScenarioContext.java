package io.pickleball.cacheandstate;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.core.runner.TestCase;
import io.cucumber.core.runner.TestCaseState;
import io.pickleball.mapandStateutilities.LinkedMultiMap;

import java.util.*;

import static io.pickleball.cacheandstate.PrimaryScenarioData.*;
import static io.pickleball.executions.ComponentRuntime.createTestcases;
import static java.util.Comparator.comparingInt;

public abstract class ScenarioContext extends  BaseContext implements io.cucumber.plugin.event.TestStep {
    private final Pickle pickle;             // The static scenario definition
    private TestCaseState testCaseState;    // The mutable scenario state

    public int nestingLevel = 0;
    public int position = 0;
    public TestCase parent = null;
    private List<ScenarioContext> children = new ArrayList<>();
    private List<StepContext> stepChildren = new ArrayList<>();

    public boolean isTopLevel() {
        return nestingLevel == 0;
    }

    public Stack<PickleStepTestStep> getExecutingStepStack() {
        return executingStepStack;
    }

    private final Stack<PickleStepTestStep> executingStepStack = new Stack<>();

    public static void setCurrentStep(PickleStepTestStep currentStep) {
        getCurrentScenario().getExecutingStepStack().add(currentStep);
    }
    public static StepContext popCurrentStep() {
        return getCurrentScenario().getExecutingStepStack().pop();
    }

    public PickleStepTestStep parentStep;

    public void addChildScenarioContext(TestCase child) {
        child.parent = (TestCase) this;
        child.nestingLevel = (nestingLevel + 1);
        child.position = children.size();
        children.add(child);
        child.parentStep = getCurrentStep();
    }

    public void addChildStepContext(StepContext child) {
        child.parent = this;
        child.nestingLevel = (nestingLevel + 1);
        child.position = stepChildren.size();
        stepChildren.add(child);
    }


    public ScenarioContext(UUID id, Pickle pickle) {
        this.pickle = pickle;
    }

//    public void createComponentScenario(String argString, LinkedMultiMap<String, String>... maps) {
//        createComponentScenario(convertCommandLineToArgv(argString), maps);
//    }
//
//    public void createComponentScenario(Map<String, Object> map, LinkedMultiMap<String, String>... maps) {
//        createComponentScenario(convertHashMapToArgv(map), maps);
//    }

    public List<TestCase> getAndSortTestcases(String[] args, LinkedMultiMap<String, String>... maps) {
        List<TestCase> tests = createTestcases(args, maps);
        List<TestCase> testCases = new ArrayList<>(tests);
        testCases.sort(
                comparingInt(TestCase::getPriority)
                        .thenComparing(TestCase::getName)
                        .thenComparingInt(TestCase::getLine));
        return testCases;
    }

    public void executeTestCases(List<TestCase> testCases) {
        for (TestCase testCase : testCases) {
            addChildScenarioContext(testCase);
            testCase.runComponent(getRunner().bus);
        }
    }

    public void createComponentScenario(String[] args, LinkedMultiMap<String, String>... maps) {
        executeTestCases(getAndSortTestcases(args, maps));
    }


    public ScenarioContext getRootScenarioContext() {
        if (parent == null)
            return this;
        return getRootScenarioContext();
    }

    public UUID getRootId() {
        return getRootScenarioContext().getId();
    }



    @Override
    public String getCodeLocation() {
        return parentStep.getCodeLocation();
    }


    public Pickle getPickle() {
        return pickle;
    }


    public TestCaseState getTestCaseState() {
        return testCaseState;
    }

    public void setTestCaseState(TestCaseState testCaseState) {
        this.testCaseState = testCaseState;
    }


}
