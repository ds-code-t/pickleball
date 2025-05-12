package io.pickleball.cacheandstate;

import io.cucumber.core.backend.Status;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.exception.CucumberException;
import io.cucumber.core.gherkin.DocStringArgument;
import io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument;
import io.cucumber.core.gherkin.messages.GherkinMessagesPickle;
import io.cucumber.core.gherkin.messages.GherkinMessagesStep;
import io.cucumber.core.runner.*;
import io.cucumber.core.runner.TestCase;
import io.cucumber.datatable.DataTable;
import io.cucumber.messages.types.*;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.pickleball.exceptions.PickleballException;
import io.pickleball.mapandStateutilities.LinkedMultiMap;
import io.pickleball.mapandStateutilities.MapsWrapper;

import java.util.*;
import java.util.stream.Stream;

import static io.cucumber.core.gherkin.messages.GherkinMessagesStep.*;
import static io.cucumber.gherkin.PickleCompiler.pickleStepTypeFromKeywordType;
import static io.pickleball.StepFactory.createGherkinMessagesStep;
import static io.pickleball.cacheandstate.PrimaryScenarioData.getCurrentState;

import static io.pickleball.mapandStateutilities.MappingFunctions.replaceNestedBrackets;

public class StepWrapper extends BaseContext {
    public final List<LinkedMultiMap<String, Object>> tableMaps = new ArrayList<>();

    private final PickleStepTestStep templateStep;
//    private final TestCase parentTestCase;

    public GherkinMessagesStep getGherkinMessagesStep() {
        return gherkinMessagesStep;
    }

    public static final String TABLE_ROW_LOOP = "-TABLE-ROW-LOOP-";
//    public int tableRowCount = 0;

    private final GherkinMessagesStep gherkinMessagesStep;

    private List<StepWrapper> nestedChildSteps;

    private StepWrapper parentStepWrapper;

    public int getNestingLevel() {
        return nestingLevel;
    }

    private final int nestingLevel;
    private final int wrapperNumber;

    public final String stepWrapperKey;

//    private List<LinkedMultiMap<String, String>> attachedStepMaps;
//    private String attachedStepStringArg;


    public StepWrapper(PickleStepTestStep templateStep, TestCase testCase, int wrapperNumber) {
        this.templateStep = templateStep;
        this.gherkinMessagesStep = (GherkinMessagesStep) templateStep.getStep();
        this.parentTestCase = testCase;
        this.nestingLevel = gherkinMessagesStep.getColonNesting();
        this.wrapperNumber = wrapperNumber;
        this.stepWrapperKey = templateStep.getUri() + ":" + templateStep.getStep().getLine();

//        io.cucumber.core.gherkin.Argument arg = getGherkinMessagesStep().getArgument();
//        if (arg != null) {
//            if ((arg instanceof DataTable)) {
//                attachedStepMaps = ((DataTable) arg).asLinkedStringMultiMaps();
//            } else if ((arg instanceof DocStringArgument)) {
//                attachedStepStringArg = ((DocStringArgument) arg).getContent();
//            }
//        }
    }


    private final List<PickleStepTestStep> clonedSteps = new ArrayList<>();


//    public ExecutionMode run(TestCase testCase, EventBus bus, TestCaseState state, ExecutionMode startingExecutionMode, io.cucumber.core.runner.PickleStepTestStep parentStep) {
//        return run( testCase,  bus,  state,  startingExecutionMode,  parentStep, -1);
//    }

    public ExecutionMode run(TestCase testCase, EventBus bus, TestCaseState state, ExecutionMode startingExecutionMode, io.cucumber.core.runner.PickleStepTestStep parentStep,   LinkedMultiMap<String, Object>... stepTables) {

        LinkedMultiMap<String, Object>[] newStepMaps = parentStep == null ? stepTables : Stream.concat(parentStep.getAllStepMaps().stream() ,Arrays.stream(stepTables))
                .toArray(LinkedMultiMap[]::new);

        io.cucumber.core.runner.PickleStepTestStep clone = modifyPickleStepTestStep(newStepMaps);
        clone.addInheritedMaps(stepTables);
        addCloned(clone);

        ExecutionMode runExecutionMode;
        String runFlag = gherkinMessagesStep.getRunFlag();

        if (parentStep != null) {
            clone.setShouldUpdateStatus(parentStep.shouldUpdateStatus());
            clone.setInheritedMaps(parentStep.getAllStepMaps());
        }

        if (runFlag.isEmpty()) {
            runExecutionMode = startingExecutionMode;
        } else {
            clone.setShouldUpdateStatus(false);
            if (parentStep == null || parentStep.getHighestStatus().equals(io.cucumber.plugin.event.Status.PASSED) || parentStep.getHighestStatus().equals(io.cucumber.plugin.event.Status.SOFT_FAILED))
                runExecutionMode = getRunExecutionMode(startingExecutionMode, runFlag);
            else
                runExecutionMode = startingExecutionMode;
        }


        testCase.setCurrentWrapperNum(wrapperNumber);
        ExecutionMode returnExecutionMode = clone.run(testCase, bus, state, runExecutionMode);
        ExecutionMode passedExecutionMode = runFlag.isEmpty() ? returnExecutionMode : startingExecutionMode;




        if (nestedChildSteps == null || !clone.shouldRunNestedSteps() || !returnExecutionMode.equals(ExecutionMode.RUN))
            return passedExecutionMode;


        for (StepWrapper nestedStepWrapper : getNestedChildSteps()) {
            ExecutionMode nestedStepExecutionMode = clone.shouldForceRunNestedSteps() ? ExecutionMode.RUN : returnExecutionMode;
            returnExecutionMode = nestedStepWrapper.run(testCase, bus, state, nestedStepExecutionMode, clone);
        }

//        System.out.println("@@wrapperNumber2: " + wrapperNumber);
//        testCase.setCurrentWrapperNum(wrapperNumber);


        if (parentTestCase.isForceComplete())
            return ExecutionMode.END_SCENARIO;

        return passedExecutionMode;
    }

    public void addCloned(io.cucumber.core.runner.PickleStepTestStep clone) {
        clone.stepWrapper = this;
        clonedSteps.add(clone);
    }

    public DataTable getDataTable() {
        io.cucumber.core.gherkin.Argument arg = getGherkinMessagesStep().getArgument();
//        io.cucumber.datatable.DataTable arg = tab
        if (arg == null) {
            throw new PickleballException("No Data Table argument for: " + stepWrapperKey);
        }
        if (!(arg instanceof GherkinMessagesDataTableArgument)) {
            throw new PickleballException("arg is " + arg.getClass() + " and not a datatable for step: " + stepWrapperKey);
        }
        return DataTable.from((GherkinMessagesDataTableArgument) arg);
    }

    public List<LinkedMultiMap<String, String>> getDataMaps() {
        return getDataTable().asLinkedMultiMaps(String.class, String.class);
    }


    public io.cucumber.core.runner.PickleStepTestStep modifyPickleStepTestStep(  Map<String, Object>... additionalMaps) {
        return createPickleStepTestStep(parentTestCase.getRunner(), createPickleStep(   additionalMaps), parentTestCase.getPickle());
    }


    public io.cucumber.core.runner.PickleStepTestStep createPickleStepTestStep(Runner runner, PickleStep pickleStep, GherkinMessagesPickle pickle) {
        GherkinMessagesStep gherkinMessagesStep = createGherkinMessagesStep(pickleStep, pickle);
        gherkinMessagesStep.copyTemplateParameters(getGherkinMessagesStep());
        PickleStepDefinitionMatch match = runner.matchStepToStepDefinition(pickle, gherkinMessagesStep);
        if (match.method == null)
            throw new CucumberException("No matching method found for step '" + pickleStep.getText() + "'");

        List<HookTestStep> afterStepHookSteps = runner.createAfterStepHooks(pickle.getTags());
        List<HookTestStep> beforeStepHookSteps = runner.createBeforeStepHooks(pickle.getTags());
        io.cucumber.core.runner.PickleStepTestStep returnPickle =
                new io.cucumber.core.runner.PickleStepTestStep(
                        runner.bus.generateId(), pickle.getUri(),
                        gherkinMessagesStep, beforeStepHookSteps,
                        afterStepHookSteps, match);
//        returnPickle.setTableRowCounter(tableRowCount);
        return returnPickle;
    }


    public PickleStep createPickleStep(  Map<String, Object>... additionalMaps) {
//        PickleStepTestStep pickleStepTestStep = (PickleStepTestStep) this;
        GherkinMessagesStep gherkinMessagesStep = getGherkinMessagesStep();
        PickleStep pickleStep = gherkinMessagesStep.getPickleStep();
        Step step = pickleStep.getStepTemplate();

        MapsWrapper stepMapper = parentTestCase.runMaps.createNewMapWrapper(additionalMaps);
        String stepText = String.valueOf(replaceNestedBrackets(templateStep.getStep().getText(), stepMapper));
        PickleStepArgument argument = null;

        io.cucumber.messages.types.DataTable dataTable = step.getDataTable().orElse(null);

        if (dataTable != null) {
            List<TableRow> rows = dataTable.getRows();
            List<PickleTableRow> newRows = new ArrayList<>(rows.size());
            for (TableRow row : rows) {
                List<TableCell> cells = row.getCells();
                List<PickleTableCell> newCells = new ArrayList<>();
                for (TableCell cell : cells) {
                    String cellText = String.valueOf(replaceNestedBrackets(cell.getValue(), stepMapper));
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
                    media = String.valueOf(replaceNestedBrackets(media, stepMapper));
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
        step.parentStepWrapper = this;
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

//    public List<String> getFlagList() {
//        return gherkinMessagesStep.getFlagList();
//    }


    public ExecutionMode getRunExecutionMode(ExecutionMode startingExecutionMode, String runFlag) {
        Status status = getCurrentState().getStatus();
        if (runFlag.contains(RUN_ALWAYS) || runFlag.contains(RUN))
            return ExecutionMode.RUN;

        if (runFlag.contains(RUN_ON_FAIL))
            return (status == Status.FAILED || status == Status.SOFT_FAILED) ? ExecutionMode.RUN : ExecutionMode.SKIP;

        if (runFlag.contains(RUN_ON_SOFT_FAIL))
            return status == Status.SOFT_FAILED ? ExecutionMode.RUN : ExecutionMode.SKIP;

        if (runFlag.contains(RUN_ON_HARD_FAIL))
            return status == Status.FAILED ? ExecutionMode.RUN : ExecutionMode.SKIP;

        if (runFlag.contains(RUN_ON_PASS))
            return status == Status.PASSED ? ExecutionMode.RUN : ExecutionMode.SKIP;

        throw new PickleballException("Invalid run flag: '" + runFlag + "'");
    }

//
//    public boolean isForceRun() {
//        String runFlag = gherkinMessagesStep.getRunFlag();
//        if (runFlag.isEmpty())
//            return false;
//
//        if (runFlag.contains(RUN_ALWAYS) || runFlag.contains(RUN_IF))
//            return true;
//
//        if (runFlag.contains(RUN_ON_FAIL))
//            return (getCurrentState().getStatus() == Status.FAILED || getCurrentState().getStatus() == Status.SOFT_FAILED);
//
//        if (runFlag.contains(RUN_ON_SOFT_FAIL))
//            return getCurrentState().getStatus() == Status.SOFT_FAILED;
//
//        if (runFlag.contains(RUN_ON_HARD_FAIL))
//            return getCurrentState().getStatus() == Status.FAILED;
//
//        if (runFlag.contains(RUN_ON_PASS))
//            return getCurrentState().getStatus() == Status.PASSED;
//
//        return false;
//
//    }


}
