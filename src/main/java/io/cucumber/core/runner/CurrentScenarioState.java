package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.mappings.ScenarioMapping;
import tools.dscode.common.status.SoftExceptionInterface;

import java.time.Duration;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getTestCaseState;
import static tools.dscode.common.GlobalConstants.ALWAYS_RUN;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_HARD_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_PASSING;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_SOFT_FAILED;
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

//    public void runStepExtensions() {
//        System.out.println("@@runStepExtensions: " + this.stepExtensions.getFirst().pickleStepTestStep.getStep().getText());
//        this.stepExtensions.getFirst().run();
//    }

    public void startScenarioRun() {
        StepExtension rootScenarioStep = testCase.getRootScenarioStep();
//        rootScenarioStep.addDefinitionFlag(DefinitionFlag.NO_LOGGING);
        testCaseState = getTestCaseState();
        rootScenarioStep.runMethodDirectly = true;
        runStep(rootScenarioStep);
    }

    public void runStep(StepExtension stepExtension) {
        System.out.println("@@runStep: " + stepExtension);
        if (!shouldRun(stepExtension)) return;

        Result result;
        if (stepExtension.runMethodDirectly) {
            System.out.println("@@runStep:a: " + stepExtension);
            stepExtension.runPickleStepDefinitionMatch();
            System.out.println("@@runStep:b: " + stepExtension);
            result = new Result(Status.PASSED, Duration.ZERO, null);
            System.out.println("@@runStep:c: " + stepExtension);
        } else {
            System.out.println("@@runStep:d: " + stepExtension);
            result = stepExtension.run();
            System.out.println("@@runStep:e: " + stepExtension);
        }
        System.out.println("@@runStep:f: " + stepExtension);
        System.out.println("@@result2:: " + result);
        io.cucumber.plugin.event.Status status = result.getStatus();
        if (!result.getStatus().equals(io.cucumber.plugin.event.Status.PASSED)) {
            Throwable throwable = result.getError();
            if (throwable == null) {
                if (status.equals(io.cucumber.plugin.event.Status.UNDEFINED))
                    throwable = new RuntimeException("'" + stepExtension.pickleStepTestStep.getStep().getText() + "' step is undefined");
                else
                    throwable = new RuntimeException("Step failed with status: " + status);
            }
            System.out.println("@@after-runStep1: " + stepExtension);
            System.out.println("@@after-runStep2: " + throwable);
            if (throwable != null) {
                System.out.println("@@throwable: " + throwable.getMessage());
                if (SoftExceptionInterface.class.isAssignableFrom(throwable.getClass()))
                    isScenarioSoftFail = true;
                else {
                    isScenarioHardFail = true;
                    isScenarioSoftFail = false;
                }
            }
        }

        System.out.println("@@runStep2: " + stepExtension);
        if (isScenarioComplete())
            return;
        System.out.println("@@runStep3: " + stepExtension);
        for (StepData attachedStep : stepExtension.attachedSteps) {
            runStep((StepExtension) attachedStep);
        }

        StepData firstChild = stepExtension.initializeChildSteps();
        System.out.println("@@firstChild: " + firstChild);
        if (firstChild != null) runStep((StepExtension) firstChild);

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
