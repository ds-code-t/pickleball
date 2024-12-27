package io.pickleball.cacheandstate;


import io.cucumber.core.backend.Status;
import io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument;
import io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument;
import io.cucumber.core.gherkin.messages.GherkinMessagesStep;
import io.cucumber.core.stepexpression.Argument;
import io.cucumber.core.stepexpression.DataTableArgument;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.java.StepDefinitionAnnotations;
import io.cucumber.messages.types.*;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.core.runner.PickleStepDefinitionMatch;
import io.pickleball.annotations.NoEventEmission;
import io.pickleball.logging.EventContainer;

import java.lang.annotation.Annotation;
import java.util.*;

import static io.cucumber.gherkin.PickleCompiler.pickleStepTypeFromKeywordType;
import static io.pickleball.StepFactory.createPickleStepTestStep;
import static io.pickleball.cacheandstate.PrimaryScenarioData.shouldSendEvent;
import static io.pickleball.mapandStateutilities.MappingFunctions.replaceNestedBrackets;


public class StepContext extends BaseContext {


//    public int nestingLevel = 0;
//    public int position = 0;
//    public ScenarioContext parent = null;


    public EventContainer startEvent;
    public EventContainer endEvent;

    public boolean sendEvents = true;
    public boolean sendStart = false;
    public boolean sendEnd = false;
    public boolean startEventSent = false;
    public boolean endEventSent = false;


    private ScenarioContext scenarioContext;
    private final PickleStepDefinitionMatch pickleStepDefinitionMatch;
    public UUID id;


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


    public io.cucumber.core.runner.PickleStepTestStep modifyPickleStepTestStep() {
        return createPickleStepTestStep(parent.getRunner(), createPickleStep(), parent.getPickle());
    }

    public PickleStep createPickleStep() {
        PickleStepTestStep pickleStepTestStep = (PickleStepTestStep) this;
        GherkinMessagesStep gherkinMessagesStep = (GherkinMessagesStep) pickleStepTestStep.getStep();
        PickleStep pickleStep = gherkinMessagesStep.getPickleStep();
        Step step = pickleStep.getStepTemplate();


        String stepText = replaceNestedBrackets(pickleStepTestStep.getStep().getText(), parent.getPassedMap(), parent.getExamplesMap(), parent.getStateMap());

        PickleStepArgument argument = null;

        io.cucumber.messages.types.DataTable dataTable = step.getDataTable().orElse(null);

        if (dataTable != null) {
            List<TableRow> rows = dataTable.getRows();
            List<PickleTableRow> newRows = new ArrayList<>(rows.size());
            for (TableRow row : rows) {
                List<TableCell> cells = row.getCells();
                List<PickleTableCell> newCells = new ArrayList<>();
                for (TableCell cell : cells) {
                    String cellText = replaceNestedBrackets(cell.getValue(), parent.getPassedMap(), parent.getExamplesMap(), parent.getStateMap());
                    newCells.add(new PickleTableCell(cellText));
                }
                newRows.add(new PickleTableRow(newCells));
            }
            argument = new PickleStepArgument(null, new PickleTable(newRows));
        } else {
            io.cucumber.messages.types.DocString docString = step.getDocString().orElse(null);
            if (docString != null) {

                String media = docString.getMediaType().orElse(null);
                if (media != null)
                    media = replaceNestedBrackets(media, parent.getPassedMap(), parent.getExamplesMap(), parent.getStateMap());
                String content = docString.getContent();

                PickleDocString pickleDocString = new PickleDocString(media, content);
                argument = new PickleStepArgument(pickleDocString, null);


            }
        }


        return new PickleStep(
                step,
                argument,
                pickleStep.getAstNodeIds(),
                pickleStep.getId(),
                pickleStepTypeFromKeywordType.get(gherkinMessagesStep.getPickleStep().getStepTemplate()),
                stepText
        );

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
