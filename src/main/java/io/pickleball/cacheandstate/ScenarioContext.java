package io.pickleball.cacheandstate;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.TestCase;
import io.cucumber.core.runner.TestCaseState;
import io.pickleball.mapandStateutilities.LinkedMultiMap;
//import io.pickleball.mapandStateutilities.LinkedMultiMap;

import java.util.*;

import static io.pickleball.cacheandstate.PrimaryScenarioData.*;
import static io.pickleball.executions.ComponentRuntime.createTestcases;
//import static io.pickleball.cacheandstate.GlobalCache.getGlobalRunner;
import static io.cucumber.messages.Convertor.toMessage;
import static io.cucumber.utilities.ArgumentParsing.convertCommandLineToArgv;
import static io.cucumber.utilities.ArgumentParsing.convertHashMapToArgv;
import static java.util.Comparator.comparing;
import static java.util.Comparator.comparingInt;

public class ScenarioContext implements io.cucumber.plugin.event.TestStep {
    private final Pickle pickle;             // The static scenario definition
    private TestCase testCase;              // The runtime scenario object
    private TestCaseState testCaseState;    // The mutable scenario state
    private String codeLocation;

    int nestingLevel = 0;
    private int position = 0;
    public ScenarioContext parent = null;
    private List<ScenarioContext> children = new ArrayList<>();
    private List<StepContext> stepChildren = new ArrayList<>();

//    private StepContext currentStep;

    public boolean isTopLevel() {
        return nestingLevel == 0;
    }

//    public void addResult(Result result) {
//        testCaseState.add(result);
//        if(parentStep != null)
//            parentStep.addResults(result);
//    }
//    private StepContext currentStep;
    private final Stack<StepContext> executingStepStack = new Stack<>();

    public static StepContext getCurrentStep() {
        return getCurrentScenario().executingStepStack.peek();
    }

    public static void setCurrentStep(StepContext currentStep) {
        getCurrentScenario().executingStepStack.add(currentStep);
    }
    public static StepContext popCurrentStep() {
        return getCurrentScenario().executingStepStack.pop();
    }

    public StepContext parentStep;

    public void addChildScenarioContext(ScenarioContext child) {
        child.parent = this;
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


    public ScenarioContext(Pickle pickle) {
        this.pickle = pickle;
    }

    public void createComponentScenario(String argString, LinkedMultiMap<String, String>... maps) {
        createComponentScenario(convertCommandLineToArgv(argString), maps);
    }

    public void createComponentScenario(Map<String, Object> map, LinkedMultiMap<String, String>... maps) {
        createComponentScenario(convertHashMapToArgv(map), maps);
    }

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
            addChildScenarioContext(testCase.scenarioContext);
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
    public UUID getId() {
        return testCase.getId();
    }

    @Override
    public String getCodeLocation() {
//        return codeLocation;
        return parentStep.getTestStep().getCodeLocation();
    }

    public void setCodeLocation(String codeLocation) {
        this.codeLocation = codeLocation;
    }

    public Pickle getPickle() {
        return pickle;
    }

    public TestCase getTestCase() {
        return testCase;
    }

    public void setTestCase(TestCase testCase) {
        this.testCase = testCase;
    }

    public TestCaseState getTestCaseState() {
        return testCaseState;
    }

    public void setTestCaseState(TestCaseState testCaseState) {
        this.testCaseState = testCaseState;
    }


}
