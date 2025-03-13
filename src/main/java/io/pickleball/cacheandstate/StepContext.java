package io.pickleball.cacheandstate;


import io.cucumber.core.backend.Status;
import io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument;
import io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument;
import io.cucumber.core.runner.ExecutionMode;
import io.cucumber.core.stepexpression.Argument;
import io.cucumber.core.stepexpression.DataTableArgument;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.core.runner.PickleStepDefinitionMatch;
import io.pickleball.annotations.NoEventEmission;
import io.pickleball.logging.EventContainer;
import java.util.*;
import static io.pickleball.cacheandstate.PrimaryScenarioData.*;

public class StepContext {



    public boolean shouldUpdateStatus() {
        return updateStatus;
    }

    public void setShouldUpdateStatus(boolean updateStatus) {
        this.updateStatus = updateStatus;
    }

    protected boolean updateStatus = true;


    public final List<Map<String, Object>> executionMapList = new ArrayList<>();
    public EventContainer startEvent;
    public EventContainer endEvent;

    public boolean sendEvents = true;
    public boolean sendStart = false;
    public boolean sendEnd = false;
    public boolean startEventSent = false;
    public boolean endEventSent = false;


    //    private ScenarioContext scenarioContext;
    private final PickleStepDefinitionMatch pickleStepDefinitionMatch;
    public UUID id;

    private boolean runNestedSteps = true;

     public boolean shouldRunNestedSteps() {
        return runNestedSteps;
    }

    public void setRunNestedSteps(boolean runNestedSteps) {
        this.runNestedSteps = runNestedSteps;
    }

    private boolean forceRunNestedSteps = false;

    public boolean shouldForceRunNestedSteps() {
        return forceRunNestedSteps;
    }

    public void setForceRunNestedSteps(boolean forceRunNestedSteps) {
        this.forceRunNestedSteps = forceRunNestedSteps;
    }


    protected ExecutionMode runExecutionMode = null;

    protected String runChildrenOverride = "";
    protected String runOverride = "";




    List<io.cucumber.plugin.event.Status> statuses = new ArrayList<>();


    public void newExecutionMapPut(String key, Object value) {
        Map<String, Object> executionMap = new HashMap<>();
        executionMap.put(key, value);
        executionMapList.add(executionMap);
    }

    public Object getLastExecutionReturnValue() {
        return getExecutionMap().get("returnValue");
    }


    public static void currentExecutionMapPut(String key, Object value) {
        getCurrentStep().getExecutionMap().put(key, value);
    }

    public Map<String, Object> getExecutionMap() {
        return executionMapList.get(executionMapList.size() - 1);
    }


    public boolean shouldEmitEvent() {
        return !pickleStepDefinitionMatch.method.isAnnotationPresent(NoEventEmission.class);
    }


    public StepContext(
            UUID id,
            PickleStepDefinitionMatch pickleStepDefinitionMatch
    ) {
        this.id = id;
        this.pickleStepDefinitionMatch = pickleStepDefinitionMatch;
    }

    protected StepWrapper stepWrapper;


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



}
