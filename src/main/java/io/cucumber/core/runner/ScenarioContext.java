package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runtime.TimeServiceEventBus;
import io.cucumber.core.runtime.UuidGeneratorServiceLoader;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.TestStepResult;
import io.cucumber.plugin.event.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.cucumber.core.runner.TestStepResultStatusMapper.from;
import static io.cucumber.core.runtime.ComponentRuntime.createTestcases;
import static io.cucumber.core.runtime.GlobalCache.getGlobalRunner;
import static io.cucumber.messages.Convertor.toMessage;
import static io.cucumber.utilities.ArgumentParsing.convertCommandLineToArgv;
import static io.cucumber.utilities.ArgumentParsing.convertHashMapToArgv;

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

    private StepContext currentStep;

    public boolean isTopLevel() {
        return nestingLevel == 0;
    }

    public StepContext getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(StepContext currentStep) {
        this.currentStep = currentStep;
    }

    public StepContext parentStep;

    public void addChildScenarioContext(ScenarioContext child) {
        child.parent = this;
        child.nestingLevel = (nestingLevel + 1);
        child.position = children.size();
        children.add(child);
        child.parentStep = currentStep;
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

    public void createComponentScenario(String argString) {
        createComponentScenario(convertCommandLineToArgv(argString));
    }

    public void createComponentScenario(Map<String, Object> map) {
        createComponentScenario(convertHashMapToArgv(map));
    }

    public void createComponentScenario(String[] args) {
        List<TestCase> testCases = createTestcases(args, this);
        System.out.println("@@testCases: " + testCases.size());
        for (TestCase testCase : testCases) {
            addChildScenarioContext(testCase.scenarioContext);
            testCase.runComponent(getGlobalRunner().bus);
        }
    }
    public ScenarioContext getRootScenarioContext() {
        if(parent == null)
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
