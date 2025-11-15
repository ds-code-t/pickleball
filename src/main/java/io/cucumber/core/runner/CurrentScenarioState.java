package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.stepexpression.Argument;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ScenarioMapping;
import tools.dscode.common.status.SoftExceptionInterface;

import java.time.Duration;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getGherkinMessagesPickle;
import static io.cucumber.core.runner.GlobalState.getTestCase;
import static io.cucumber.core.runner.GlobalState.getTestCaseState;
import static tools.dscode.common.GlobalConstants.ALWAYS_RUN;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_HARD_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_PASSING;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_SOFT_FAILED;
import static tools.dscode.common.annotations.DefinitionFlag.SKIP_CHILDREN;
import static tools.dscode.common.util.Reflect.getProperty;

public class CurrentScenarioState extends ScenarioMapping {

    public final TestCase testCase;
    public final Pickle pickle;
    List<StepExtension> stepExtensions;
    private TestCaseState testCaseState;

    public StepExtension getCurrentStep() {
        return currentStep;
    }

    private StepExtension currentStep;

//    public ScenarioStep rootScenarioStep;

    public CurrentScenarioState(TestCase testCase) {
        this.testCase = testCase;
        this.pickle = (Pickle) getProperty(testCase, "pickle");
        this.stepExtensions = (List<StepExtension>) getProperty(testCase, "stepExtensions");
    }

    private final LifecycleManager lifecycle = new LifecycleManager();

    public void startScenarioRun() {
        lifecycle.fire(Phase.BEFORE_SCENARIO_RUN);

        StepExtension rootScenarioStep = testCase.getRootScenarioStep();
        Pickle gherkinMessagesPickle = (Pickle) getProperty(testCase, "pickle");
        io.cucumber.messages.types.Pickle pickle = (io.cucumber.messages.types.Pickle) getProperty(gherkinMessagesPickle, "pickle");
        rootScenarioStep.setStepParsingMap(getParsingMap());
        if (pickle.getValueRow() != null && !pickle.getValueRow().isEmpty()) {
            NodeMap examples = new NodeMap(MapConfigurations.MapType.EXAMPLE_MAP);
            examples.merge(pickle.getHeaderRow(), pickle.getValueRow());
            rootScenarioStep.getStepParsingMap().addMaps(examples);
        }
//        rootScenarioStep.addDefinitionFlag(DefinitionFlag.NO_LOGGING);
        testCaseState = getTestCaseState();
        rootScenarioStep.runMethodDirectly = true;
        try {
            runStep(rootScenarioStep);
            if (isScenarioFailed())
                lifecycle.fire(Phase.AFTER_SCENARIO_FAIL);
            else
                lifecycle.fire(Phase.AFTER_SCENARIO_PASS);
        } catch (Throwable t) {
            lifecycle.fire(Phase.AFTER_SCENARIO_FAIL);
        }
        lifecycle.fire(Phase.AFTER_SCENARIO_RUN);
    }

    public void runStep(StepExtension stepExtension) {
        currentStep = stepExtension;
        if (!shouldRun(stepExtension)) return;

        Result result;
        if (stepExtension.runMethodDirectly) {
            stepExtension.runPickleStepDefinitionMatch();
            result = new Result(Status.PASSED, Duration.ZERO, null);
        } else {
            result = stepExtension.run();
        }


        io.cucumber.plugin.event.Status status = result.getStatus();
        if (!result.getStatus().equals(io.cucumber.plugin.event.Status.PASSED)) {
            Throwable throwable = result.getError();
            if (throwable == null) {
                if (status.equals(io.cucumber.plugin.event.Status.UNDEFINED))
                    throwable = new RuntimeException("'" + stepExtension.pickleStepTestStep.getStep().getText() + "' step is undefined");
                else
                    throwable = new RuntimeException("Step failed with status: " + status);
            }
            if (throwable != null) {
                if (SoftExceptionInterface.class.isAssignableFrom(throwable.getClass()))
                    isScenarioSoftFail = true;
                else {
                    isScenarioHardFail = true;
                    isScenarioSoftFail = false;
                }
            }
        }

        if (isScenarioComplete())
            return;
        for (StepData attachedStep : stepExtension.attachedSteps) {
            runStep((StepExtension) attachedStep);
        }
        if (!stepExtension.childSteps.isEmpty() && !stepExtension.definitionFlags.contains(SKIP_CHILDREN)) {
            StepExtension firstChild = (StepExtension) stepExtension.initializeChildSteps();
            if (firstChild != null)
                runStep(firstChild);
        }
        if (stepExtension.nextSibling != null) {
            runStep((StepExtension) stepExtension.nextSibling);
        }
    }


    private boolean isScenarioHardFail = false;
    private boolean isScenarioSoftFail = false;
    private boolean isScenarioComplete = false;

    public boolean isScenarioFailed() {
        return isScenarioHardFail || isScenarioSoftFail;
    }

    public boolean isScenarioComplete() {
        return isScenarioHardFail || isScenarioComplete;
    }

    public boolean shouldRun(StepExtension stepExtension) {
        if (stepExtension.parentStep == null)
            return true;

        if (stepExtension.stepFlags.contains(ALWAYS_RUN))
            return true;

        if (stepExtension.stepFlags.contains(RUN_IF_SCENARIO_FAILED))
            return isScenarioFailed();

        if (stepExtension.stepFlags.contains(RUN_IF_SCENARIO_SOFT_FAILED))
            return isScenarioSoftFail;
        if (stepExtension.stepFlags.contains(RUN_IF_SCENARIO_HARD_FAILED))
            return isScenarioHardFail;
        if (stepExtension.stepFlags.contains(RUN_IF_SCENARIO_PASSING))
            return !isScenarioFailed();
        return !stepExtension.skipped;
    }


}
