package io.pickleball.cacheandstate;


import io.cucumber.core.backend.StepDefinition;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.predefinedsteps.metasteps.MetaStepData;
import io.cucumber.core.runner.ExecutionMode;
import io.cucumber.core.runner.PickleStepDefinitionMatch;
import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.java.JavaStepDefinition;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestStep;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;


public class StepContext {
    private ScenarioContext scenarioContext;
    private final PickleStepTestStep testStep;      // The runtime step object (e.g., PickleStepTestStep)
    private final Step gherkinStep;       // The static Gherkin step data
    private final StepDefinition stepDefinition; // The underlying step definition (stable, unchanging)
    private final PickleStepDefinitionMatch pickleStepDefinitionMatch; // The underlying step definition (stable, unchanging)

    private TestCase testCase;      // The scenario-level test case object for this step run

    private JavaStepDefinition javaStepDefinition;
//    private Method method;

    private boolean isMetaStep;


    public boolean isMetaStep() {
        return isMetaStep;
    }

    private Method method;

    public int nestingLevel = 0;
    public int position = 0;
    public ScenarioContext parent = null;


    private final List<ExecutionMode> executionModeList = new ArrayList<>();

    public ExecutionMode addExecutionMode(ExecutionMode executionMode) {
        if (executionMode.equals(ExecutionMode.RUN) && executionModeList.isEmpty())
            scenarioContext.setCurrentStep(this);
        executionModeList.add(executionMode);
        return executionMode;
    }


    public Method getMethod() {
        return method;
    }

    public void setMethod(Method method) {
        isMetaStep = method != null && method.getReturnType().equals(MetaStepData.class);
        this.method = method;
    }


    public StepContext(
            TestStep testStep,
            Step gherkinStep,
            PickleStepDefinitionMatch pickleStepDefinitionMatch
    ) {
        this.testStep = (PickleStepTestStep) testStep;
        this.gherkinStep = gherkinStep;
        this.pickleStepDefinitionMatch = pickleStepDefinitionMatch;
        this.stepDefinition = pickleStepDefinitionMatch.getStepDefinition();
        setMethod(pickleStepDefinitionMatch.method);

    }
//
//    public Object executeStepMethod() throws Throwable {
//        return javaStepDefinition.invokeMethod(pickleStepDefinitionMatch.getArgs());
//    }
//
//
//    public Object runScenarioByTags() throws Throwable {
//        return javaStepDefinition.invokeMethod(pickleStepDefinitionMatch.getArgs());
//    }


    /**
     * Returns the scenario-level context. Steps can access scenario-level data through this.
     */
    public ScenarioContext getScenarioContext() {
        return scenarioContext;
    }

    public void setScenarioContext(ScenarioContext scenarioContext) {
        scenarioContext.addChildStepContext(this);
        this.scenarioContext = scenarioContext;
    }


    /**
     * Returns the runtime TestCase for this step, which provides context about the scenario execution.
     */
    public TestCase getTestCase() {
        return testCase;
    }

    /**
     * Returns the runtime TestStep object. This may be a PickleStepTestStep or HookTestStep.
     * Useful for attaching runtime step results, logging, or accessing IDs associated with the step execution.
     */
    public TestStep getTestStep() {
        return testStep;
    }

    /**
     * Returns the static Gherkin Step object. This is the parsed, unchanging model of the step as it appears in the feature file.
     * Useful for retrieving the step text, location, arguments, and so forth.
     */
    public Step getGherkinStep() {
        return gherkinStep;
    }

    /**
     * Returns the StepDefinition object that will be invoked to execute the step logic.
     * StepDefinitions are often stable, reusable objects that are shared across runs.
     */
    public StepDefinition getStepDefinition() {
        return stepDefinition;
    }


    /**
     * If you have any reusable or stable data shared across multiple steps or runs, you can store references here.
     * For example, you might have global registries, caching systems, or static resources that steps need.
     * Add getters/setters for them as needed.
     */

    // Example:
    // private final SomeReusableResource resource;
    // public SomeReusableResource getResource() { return resource; }

    // Add more fields or methods as needed for your application’s specific requirements.
}
