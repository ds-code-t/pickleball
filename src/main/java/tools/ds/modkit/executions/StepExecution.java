package tools.ds.modkit.executions;

import io.cucumber.core.backend.TestCaseState;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.messages.types.Pickle;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.TestCase;
import tools.ds.modkit.extensions.StepExtension;
import tools.ds.modkit.mappings.NodeMap;
import tools.ds.modkit.mappings.ParsingMap;
import tools.ds.modkit.state.ScenarioState;
import tools.ds.modkit.trace.ObjDataRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//import static tools.ds.modkit.coredefinitions.MetaSteps.RUN_SCENARIO;
import static tools.ds.modkit.blackbox.BlackBoxBootstrap.skipLogging;
import static tools.ds.modkit.extensions.StepRelationships.pairSiblings;
import static tools.ds.modkit.state.GlobalState.*;
import static tools.ds.modkit.state.ScenarioState.getScenarioState;
import static tools.ds.modkit.trace.ObjDataRegistry.setFlag;
import static tools.ds.modkit.util.ExecutionModes.RUN;
import static tools.ds.modkit.util.Reflect.getProperty;


public class StepExecution {
    public final List<StepExtension> steps = new ArrayList<>();

//    public boolean isRunComplete() {
//        return runComplete;
//    }
//
//    public void endRun(boolean runComplete) {
//        this.runComplete = runComplete;
//    }
//
//    private boolean runComplete = false;

    private final StepExtension rootScenarioNameStep;

    public StepExecution(TestCase testCase) {
        List<PickleStepTestStep> pSteps = (List<PickleStepTestStep>) getProperty(testCase, "testSteps");
        setFlag(pSteps.get(pSteps.size() - 1), ObjDataRegistry.ObjFlags.LAST);

        io.cucumber.core.gherkin.Pickle pickle = getScenarioState().scenarioPickle;
        pSteps.forEach(step -> steps.add(new StepExtension(step, this, pickle)));
        ScenarioState scenarioState = getScenarioState();
        NodeMap scenarioMap = scenarioState.getScenarioMap(scenarioState.getScenarioPickle());

        Map<Integer, StepExtension> nestingMap = new HashMap<>();

        rootScenarioNameStep = new StepExtension(pickle, this, steps.getFirst().delegate);

        NodeMap runMap = new NodeMap(ParsingMap.MapType.RUN_MAP);
        rootScenarioNameStep.setStepParsingMap(new ParsingMap(runMap));
        if (scenarioMap != null) {
            scenarioMap.setDataSource(NodeMap.DataSource.EXAMPLE_TABLE);
            scenarioMap.setMapType(ParsingMap.MapType.STEP_MAP);

        }

        rootScenarioNameStep.overRideUUID = skipLogging;
        nestingMap.put(-1, rootScenarioNameStep);

        setNesting(steps, 0, nestingMap);
    }

    public static void setNesting(List<StepExtension> steps, int startingNesting, Map<Integer, StepExtension> nestingMap) {
        int size = steps.size();


//        Map<Integer, StepExtension> nestingMap = new HashMap<>();

        int lastNestingLevel = 0;


        for (int s = 0; s < size; s++) {
            StepExtension currentStep = steps.get(s);
            currentStep.setNestingLevel(currentStep.getNestingLevel() + startingNesting);
//            int currentNesting = currentStep.nestingLevel + startingNesting;
            int currentNesting = currentStep.getNestingLevel();

            StepExtension parentStep = nestingMap.get(currentNesting - 1);

            StepExtension previousSibling = currentNesting > lastNestingLevel ? null : nestingMap.get(currentNesting);

            if (previousSibling != null) {
                pairSiblings(previousSibling, currentStep);
            }
            if (parentStep != null) {
                parentStep.addChildStep(currentStep);
//                currentStep.setParentStep(parentStep);
            }

            nestingMap.put(currentNesting, currentStep);
            lastNestingLevel = currentNesting;

        }
    }


    public void runSteps(Object executionMode) {
        runSteps(getScenarioState().testCase, getScenarioState().bus, getScenarioState().getTestCaseState(), executionMode);
    }

    private boolean scenarioHardFail = false;
    private boolean scenarioSoftFail = false;
    private boolean scenarioComplete = false;

    public boolean isScenarioFailed() {
        return scenarioHardFail || scenarioSoftFail;
    }

    public boolean isScenarioHardFail() {
        return scenarioHardFail;
    }

    public boolean isScenarioSoftFail() {
        return scenarioSoftFail;
    }

    public void setScenarioHardFail() {
        this.scenarioHardFail = true;
        setScenarioComplete();
    }

    public void setScenarioSoftFail() {
        this.scenarioSoftFail = true;
    }

    public void setScenarioComplete() {
        this.scenarioComplete = true;
    }

    public boolean isScenarioComplete() {
        return this.scenarioComplete;
    }


    public void runSteps(TestCase testCase, EventBus bus, TestCaseState state, Object executionMode) {


        setFlag(testCase, ObjDataRegistry.ObjFlags.RUNNING);

        StepExtension currentStep = steps.get(0);

        rootScenarioNameStep.run(testCase, bus, state, executionMode);

//        ScenarioState scenarioState = getScenarioState();
//        StepExtension lastExecuted = currentStep.run(testCase, bus, state, executionMode);
//        while (currentStep != null) {
//            currentStep = lastExecuted.runNextSibling();
//        }


//
//        for (StepExtension step : steps) {
//            System.out.println("@@step: " + step.getStepText());
//            StepExtension newStep = step.updateStep(parsingMap);
//            newStep.run(testCase, bus, state, executionMode);
//            if (newStep.result.getStatus().equals(Status.PASSED))
//                continue;
//            Throwable throwable = newStep.result.getError();
//            if (throwable.getClass().equals(SoftException.class) || throwable.getClass().equals(SoftRuntimeException.class))
//                continue;
//            System.out.println("@@newStep.result: " + newStep.result);
//            break;
//        }

    }


}
