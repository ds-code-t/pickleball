package io.pickleball.cacheandstate;

import io.cucumber.core.backend.Status;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.exception.CucumberException;
import io.cucumber.core.gherkin.messages.GherkinMessagesPickle;
import io.cucumber.core.gherkin.messages.GherkinMessagesStep;
import io.cucumber.core.runner.*;
import io.cucumber.core.runner.TestCase;
import io.cucumber.messages.types.*;
import io.cucumber.plugin.event.PickleStepTestStep;

import java.util.ArrayList;
import java.util.List;

import static io.cucumber.core.gherkin.messages.GherkinMessagesStep.*;
import static io.cucumber.gherkin.PickleCompiler.pickleStepTypeFromKeywordType;
import static io.pickleball.StepFactory.createGherkinMessagesStep;
import static io.pickleball.cacheandstate.PrimaryScenarioData.getCurrentState;
import static io.pickleball.mapandStateutilities.MappingFunctions.replaceNestedBrackets;

public class StepWrapper extends BaseContext {
    private final PickleStepTestStep templateStep;
//    private final TestCase parentTestCase;

    public GherkinMessagesStep getGherkinMessagesStep() {
        return gherkinMessagesStep;
    }

    private final GherkinMessagesStep gherkinMessagesStep;

    private List<StepWrapper> nestedChildSteps;


    public StepWrapper(PickleStepTestStep templateStep, TestCase testCase) {
        this.templateStep = templateStep;
        this.gherkinMessagesStep = (GherkinMessagesStep) templateStep.getStep();
        this.parentTestCase = testCase;
    }

    private final List<PickleStepTestStep> clonedSteps = new ArrayList<>();

    public ExecutionMode run(TestCase testCase, EventBus bus, TestCaseState state, ExecutionMode startingExecutionMode, io.cucumber.core.runner.PickleStepTestStep parentStep) {

        io.cucumber.core.runner.PickleStepTestStep clone = modifyPickleStepTestStep();
        addCloned(clone);
        ExecutionMode dynamicExecutionMode = isForceRun() || startingExecutionMode.equals(ExecutionMode.RUN) ? ExecutionMode.RUN : ExecutionMode.SKIP;
        ExecutionMode newExecutionMode = clone.run(testCase, bus, state, dynamicExecutionMode);
        ExecutionMode returnExecutionMode = ((startingExecutionMode.equals(ExecutionMode.RUN) && (newExecutionMode.equals(ExecutionMode.RUN))) ? ExecutionMode.RUN : ExecutionMode.SKIP);
        clone.setExecutionMode(returnExecutionMode);

        if (nestedChildSteps == null || !clone.shouldRunNestedSteps())
            return returnExecutionMode;
        for (StepWrapper nestedStepWrapper : getNestedChildSteps()) {
            if (clone.isForceRun())
                returnExecutionMode = nestedStepWrapper.run(testCase, bus, state, ExecutionMode.RUN, clone);
            else
                returnExecutionMode = nestedStepWrapper.run(testCase, bus, state, returnExecutionMode, clone);
        }
        return returnExecutionMode;
    }

    public void addCloned(io.cucumber.core.runner.PickleStepTestStep clone) {
        clone.stepWrapper = this;
        clonedSteps.add(clone);
    }


    public io.cucumber.core.runner.PickleStepTestStep modifyPickleStepTestStep() {
        return createPickleStepTestStep(parentTestCase.getRunner(), createPickleStep(), parentTestCase.getPickle());
    }


    public io.cucumber.core.runner.PickleStepTestStep createPickleStepTestStep(Runner runner, PickleStep pickleStep, GherkinMessagesPickle pickle) {
        GherkinMessagesStep gherkinMessagesStep = createGherkinMessagesStep(pickleStep, pickle);
        gherkinMessagesStep.copyTemplateParameters(getGherkinMessagesStep());
        PickleStepDefinitionMatch match = runner.matchStepToStepDefinition(pickle, gherkinMessagesStep);
        if (match.method == null)
            throw new CucumberException("No matching method found for step '" + pickleStep.getText() + "'");

        List<HookTestStep> afterStepHookSteps = runner.createAfterStepHooks(pickle.getTags());
        List<HookTestStep> beforeStepHookSteps = runner.createBeforeStepHooks(pickle.getTags());
        return new io.cucumber.core.runner.PickleStepTestStep(runner.bus.generateId(), pickle.getUri(), gherkinMessagesStep, beforeStepHookSteps,
                afterStepHookSteps, match);
    }


    public PickleStep createPickleStep() {
//        PickleStepTestStep pickleStepTestStep = (PickleStepTestStep) this;
        GherkinMessagesStep gherkinMessagesStep = getGherkinMessagesStep();
        PickleStep pickleStep = gherkinMessagesStep.getPickleStep();
        Step step = pickleStep.getStepTemplate();

        String stepText = replaceNestedBrackets(templateStep.getStep().getText(), parentTestCase.mapsWrapper);

        PickleStepArgument argument = null;

        io.cucumber.messages.types.DataTable dataTable = step.getDataTable().orElse(null);

        if (dataTable != null) {
            List<TableRow> rows = dataTable.getRows();
            List<PickleTableRow> newRows = new ArrayList<>(rows.size());
            for (TableRow row : rows) {
                List<TableCell> cells = row.getCells();
                List<PickleTableCell> newCells = new ArrayList<>();
                for (TableCell cell : cells) {
                    String cellText = replaceNestedBrackets(cell.getValue(), parentTestCase.mapsWrapper);
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
                    media = replaceNestedBrackets(media, parentTestCase.mapsWrapper);
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

    public List<StepWrapper> getNestedChildSteps() {
        return nestedChildSteps;
    }

    public void addNestedChildStep(StepWrapper step) {
        if (nestedChildSteps == null)
            nestedChildSteps = new ArrayList<>();
        this.nestedChildSteps.add(step);
    }


    public String getRunTimeText() {
        return gherkinMessagesStep.getRunTimeText();
    }

    public String getKeyWord() {
        return gherkinMessagesStep.getKeyWord();
    }

    public int getColonNesting() {
        return gherkinMessagesStep.getColonNesting();
    }

    public List<String> getFlagList() {
        return gherkinMessagesStep.getFlagList();
    }



    public boolean isForceRun() {
        List<String> flagList = getFlagList();
        if (flagList == null)
            return false;

        if (flagList.contains(RUN_ALWAYS) || flagList.contains(RUN_IF))
            return true;

        if (flagList.contains(RUN_ON_FAIL))
            return (getCurrentState().getStatus() == Status.FAILED || getCurrentState().getStatus() == Status.SOFT_FAILED);

        if (flagList.contains(RUN_ON_SOFT_FAIL))
            return getCurrentState().getStatus() == Status.SOFT_FAILED;

        if (flagList.contains(RUN_ON_HARD_FAIL))
            return getCurrentState().getStatus() == Status.FAILED;

        if (flagList.contains(RUN_ON_PASS))
            return getCurrentState().getStatus() == Status.PASSED;

        return false;

    }


}
