package io.pickleball.cacheandstate;


import io.cucumber.core.backend.Status;
import io.cucumber.core.backend.StepDefinition;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument;
import io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument;
import io.cucumber.core.runner.CoreStepDefinition;
import io.cucumber.core.stepexpression.Argument;
import io.cucumber.core.stepexpression.DataTableArgument;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.pickleball.annotations.Metastep;
import io.cucumber.core.runner.ExecutionMode;
import io.cucumber.core.runner.PickleStepDefinitionMatch;
import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.java.JavaStepDefinition;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestStep;
import io.pickleball.logging.EventContainer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static io.pickleball.cacheandstate.PrimaryScenarioData.shouldSendEvent;


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

    public EventContainer startEvent;
    public EventContainer endEvent;

    public boolean sendEvents = true;
    public boolean sendStart = false;
    public boolean sendEnd = false;
    public boolean startEventSent = false;
    public boolean endEventSent = false;


    public List<Argument> getArguments(){
        try {
            return testStep.definitionMatch.getArguments();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public GherkinMessagesDataTableArgument getGherkinMessagesDataTableArgument(){
        return getArguments().stream().filter(arg -> arg instanceof DataTableArgument).map(arg -> ((DataTable)arg.getValue()).toGherkinMessagesDataTableArgument()).findFirst().orElse(null);
    }

    public GherkinMessagesDocStringArgument getGherkinMessagesDocStringArgument(){
        return  getArguments().stream().filter(arg -> arg instanceof DocStringArgument).map(arg -> ((DocString)arg.getValue()).toGherkinMessagesDocString()).findFirst().orElse(null);
    }


    List<io.cucumber.plugin.event.Status> statuses = new ArrayList<>();

    public void addStatus(io.cucumber.plugin.event.Status status) {
        statuses.add(status);
    }
    public void addStatus(Status status) {
        statuses.add(io.cucumber.plugin.event.Status.valueOf(status.name()));
    }

    public io.cucumber.plugin.event.Status getHighestStatus() {
        if(statuses.isEmpty())
            return io.cucumber.plugin.event.Status.PASSED;
        return statuses.stream()
                .max(Enum::compareTo) // Compare by ordinal implicitly
                .orElseThrow(() -> new IllegalArgumentException("Status list is empty!"));
    }



//    private final List<io.cucumber.plugin.event.Result> stepResults = new ArrayList<>();

//    public io.cucumber.plugin.event.Status getStatus(Result... results) {
//        stepResults.addAll(Arrays.asList(results));
//        if (stepResults.isEmpty()) {
//            return io.cucumber.plugin.event.Status.valueOf(Status.PASSED.name());
//        }
//        Result mostSevereResult = max(stepResults, comparing(Result::getStatus));
//        return io.cucumber.plugin.event.Status.valueOf(mostSevereResult.getStatus().name());
//    }
//
////    public void addResults(Result... results) {
////        for(Result result: results)
////        {
////            stepResults.add(new Result(result.getStatus(), ZERO, null));
////        }
//////        stepResults.addAll(Arrays.asList(results));
////    }
//
//    public Result getMaxResults(Result... results) {
//        stepResults.addAll(Arrays.asList(results));
//        return max(stepResults, comparing(Result::getStatus));
//    }

    public void sendStartEvent() {
        if (startEvent == null)
            sendStart = true;
        else {
            String testCaseName = testCase == null ? "NULL" : testCase.getName();
            startEvent.sendStart();
            startEventSent = true;
            sendEnd = true;
            startEvent = null;
        }
    }

    public void sendEndEvent(Throwable... throwables) {
        if(shouldSendEvent(throwables)){
            sendStartEvent();
            endEvent.sendEnd();
            endEventSent = true;
            endEvent = null;
            return;
        }

        if(endEventSent)
            return;
        sendStartEvent();
        if (endEvent == null)
            sendEnd = true;
        else {
            String testCaseName = testCase == null ? "NULL" : testCase.getName();
            if (startEventSent) {
                endEvent.sendEnd();
                endEventSent = true;
                endEvent = null;
            }
        }
    }

    public boolean shouldSendStart() {
        return startEvent != null && (sendEvents || sendStart);
    }

    public boolean shouldSendEnd() {
        return endEvent != null && (sendEvents || sendEnd);
    }


//    public ExecutionMode addExecutionMode(ExecutionMode executionMode) {
//        if (executionMode.equals(ExecutionMode.RUN) && executionModeList.isEmpty())
//            setCurrentStep(this);
//        executionModeList.add(executionMode);
//        return executionMode;
//    }


    public Method getMethod() {
        return method;
    }

    public void setMethod(Method method) {
//        isMetaStep = method != null && method.getReturnType().equals(MetaStepData.class);
        isMetaStep = method != null && method.isAnnotationPresent(Metastep.class);
        this.method = method;
        this.sendEvents = !isMetaStep;
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
    public PickleStepTestStep getTestStep() {
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
