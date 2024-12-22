package io.pickleball.cacheandstate;


import io.cucumber.core.backend.Status;
import io.cucumber.core.backend.StepDefinition;
import io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument;
import io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument;
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
import java.util.UUID;

import static io.pickleball.cacheandstate.PrimaryScenarioData.shouldSendEvent;


public class StepContext extends BaseContext {


//    private ScenarioContext scenarioContext;
    //    private final PickleStepTestStep testStep;      // The runtime step object (e.g., PickleStepTestStep)
//    private final Step gherkinStep;       // The static Gherkin step data
//    private final StepDefinition stepDefinition; // The underlying step definition (stable, unchanging)
//    private final PickleStepDefinitionMatch pickleStepDefinitionMatch; // The underlying step definition (stable, unchanging)


//    private TestCase testCase;      // The scenario-level test case object for this step run

//    private JavaStepDefinition javaStepDefinition;
//    private Method method;

    private boolean isMetaStep;


    public boolean isMetaStep() {
        return isMetaStep;
    }

    private Method method;

    public int nestingLevel = 0;
    public int position = 0;
    public ScenarioContext parent = null;


//    private final List<ExecutionMode> executionModeList = new ArrayList<>();

    public EventContainer startEvent;
    public EventContainer endEvent;

    public boolean sendEvents = true;
    public boolean sendStart = false;
    public boolean sendEnd = false;
    public boolean startEventSent = false;
    public boolean endEventSent = false;


    private ScenarioContext scenarioContext;
    private StepDefinition stepDefinition; // The underlying step definition (stable, unchanging)
    private PickleStepDefinitionMatch pickleStepDefinitionMatch; // The underlying step definition (stable, unchanging)
    public UUID id;

    public StepContext(
            UUID id,
            PickleStepDefinitionMatch pickleStepDefinitionMatch
    ) {
        this.id = id;
        this.pickleStepDefinitionMatch = pickleStepDefinitionMatch;
        this.stepDefinition = pickleStepDefinitionMatch.getStepDefinition();
        setMethod(pickleStepDefinitionMatch.method);
    }




    public List<Argument> getArguments() {
        try {
            return pickleStepDefinitionMatch.getArguments();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public GherkinMessagesDataTableArgument getGherkinMessagesDataTableArgument() {
        return getArguments().stream().filter(arg -> arg instanceof DataTableArgument).map(arg -> ((DataTable) arg.getValue()).toGherkinMessagesDataTableArgument()).findFirst().orElse(null);
    }

    public GherkinMessagesDocStringArgument getGherkinMessagesDocStringArgument() {
        return getArguments().stream().filter(arg -> arg instanceof DocStringArgument).map(arg -> ((DocString) arg.getValue()).toGherkinMessagesDocString()).findFirst().orElse(null);
    }


    List<io.cucumber.plugin.event.Status> statuses = new ArrayList<>();

    public void addStatus(io.cucumber.plugin.event.Status status) {
        statuses.add(status);
    }

    public void addStatus(Status status) {
        statuses.add(io.cucumber.plugin.event.Status.valueOf(status.name()));
    }

    public io.cucumber.plugin.event.Status getHighestStatus() {
        if (statuses.isEmpty())
            return io.cucumber.plugin.event.Status.PASSED;
        return statuses.stream()
                .max(Enum::compareTo) // Compare by ordinal implicitly
                .orElseThrow(() -> new IllegalArgumentException("Status list is empty!"));
    }


    public void sendStartEvent() {
        if (startEvent == null)
            sendStart = true;
        else {
//            String testCaseName = testCase == null ? "NULL" : testCase.getName();
            startEvent.sendStart();
            startEventSent = true;
            sendEnd = true;
            startEvent = null;
        }
    }

    public void sendEndEvent(Throwable... throwables) {
        if (shouldSendEvent(throwables)) {
            sendStartEvent();
            endEvent.sendEnd();
            endEventSent = true;
            endEvent = null;
            return;
        }

        if (endEventSent)
            return;
        sendStartEvent();
        if (endEvent == null)
            sendEnd = true;
        else {
//            String testCaseName = testCase == null ? "NULL" : testCase.getName();
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
//    public TestCase getTestCase() {
//        return testCase;
//    }



}
