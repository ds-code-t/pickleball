package io.pickleball.cacheandstate;

import io.cucumber.core.backend.Status;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.messages.GherkinMessagesPickle;
import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.core.runner.Runner;
import io.cucumber.core.runner.TestCase;
import io.cucumber.core.runner.TestCaseState;
import io.cucumber.messages.types.TableCell;
import io.cucumber.messages.types.TableRow;
import io.pickleball.mapandStateutilities.LinkedMultiMap;
import io.pickleball.mapandStateutilities.MapsWrapper;

import java.util.*;

import static io.pickleball.cacheandstate.PrimaryScenarioData.*;
import static io.pickleball.cucumberutilities.ArgumentParsing.convertCommandLineToArgv;
import static io.pickleball.cucumberutilities.ArgumentParsing.convertHashMapToArgv;
import static io.pickleball.executions.ComponentRuntime.createTestcases;
import static io.pickleball.mapandStateutilities.MappingFunctions.replaceNestedBrackets;
import static java.util.Comparator.comparingInt;

public abstract class ScenarioContext extends BaseContext implements io.cucumber.plugin.event.TestStep {
    private final Pickle pickle;             // The static scenario definition
    private TestCaseState testCaseState;    // The mutable scenario state


    private final Runner runner;

    private final List<ScenarioContext> children = new ArrayList<>();
    private final List<StepContext> stepChildren = new ArrayList<>();

    private final UUID id;

    public LinkedMultiMap<String, String> getPassedMap() {
        return passedMap;
    }

    public LinkedMultiMap<String, String> getExamplesMap() {
        return examplesMap;
    }

    public LinkedMultiMap<String, String> getStateMap() {
        return stateMap;
    }

    private final LinkedMultiMap<String, String> passedMap;
    private final LinkedMultiMap<String, String> examplesMap;

    public final LinkedMultiMap<String, String> stateMap = new LinkedMultiMap<>();

    public final MapsWrapper mapsWrapper;

    public ScenarioContext(UUID id, GherkinMessagesPickle pickle, Runner runner, LinkedMultiMap<String, String> passedMap) {
        this.id = id;
        this.pickle = pickle;
        this.passedMap = passedMap;
        this.runner = runner;


        TableRow valuesRow = pickle.getMessagePickle().getValueRow();

        if (valuesRow != null) {
            List<String> headers = pickle.getMessagePickle().getHeaderRow().stream().map(TableCell::getValue).toList();
            List<String> values = valuesRow.getCells().stream().map(TableCell::getValue).toList();
            examplesMap = new LinkedMultiMap<>(headers, values);
        } else {
            examplesMap = null;
        }
        mapsWrapper = new MapsWrapper(this.passedMap, this.examplesMap, this.stateMap);
    }

    public String replaceAndEval(String inputString) {
        return replaceNestedBrackets(inputString,  mapsWrapper);
    }

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
        child.parent = (TestCase) this;
        child.nestingLevel = (nestingLevel + 1);
        child.position = stepChildren.size();
        stepChildren.add(child);
    }


    public final void createComponentScenario(String argString, LinkedMultiMap<String, String> map) {
        createComponentScenario(convertCommandLineToArgv(argString), map);
    }
//

    public final void createComponentScenario(Map<String, Object> argMap, LinkedMultiMap<String, String> map) {
        createComponentScenario(convertHashMapToArgv(argMap), map);
    }


    public final List<TestCase> getAndSortTestcases(String[] args, LinkedMultiMap<String, String> map) {
        List<TestCase> tests = createTestcases(args, map);
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

    public final void createComponentScenario(String[] args, LinkedMultiMap<String, String> map) {
        executeTestCases(getAndSortTestcases(args, map));
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


    public GherkinMessagesPickle getPickle() {
        return (GherkinMessagesPickle) pickle;
    }


    public TestCaseState getTestCaseState() {
        return testCaseState;
    }

    public void setTestCaseState(TestCaseState testCaseState) {
        this.testCaseState = testCaseState;
    }

    public Runner getRunner() {
        return runner;
    }

    public List<ScenarioContext> getChildren() {
        return children;
    }

    public List<StepContext> getStepChildren() {
        return stepChildren;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public PickleStepTestStep getParentStep() {
        return parentStep;
    }

    public void setScenarioStatus(Status status){
        getTestCaseState().setCompletionStatus(status);
    }
    public void setTestStatus(Status status){
        getPrimaryState().setCompletionStatus(status);
        getTestCaseState().setCompletionStatus(status);
    }
}
