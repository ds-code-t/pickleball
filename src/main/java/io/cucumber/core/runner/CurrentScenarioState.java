package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ScenarioMapping;
import tools.dscode.common.status.SoftExceptionInterface;
import tools.dscode.registry.GlobalRegistry;

import java.time.Duration;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getTestCaseState;
import static tools.dscode.common.GlobalConstants.ALWAYS_RUN;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_HARD_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_PASSING;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_SOFT_FAILED;
import static tools.dscode.common.annotations.DefinitionFlag.SKIP_CHILDREN;
import static tools.dscode.common.util.DebugUtils.printDebug;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.registry.GlobalRegistry.getScenarioWebDrivers;

public class CurrentScenarioState extends ScenarioMapping {

    public final TestCase testCase;
    public final Pickle pickle;
    List<StepExtension> stepExtensions;
    StepExtension startStep;
    private TestCaseState testCaseState;

    public StepExtension getCurrentStep() {
        return currentStep;
    }

    private StepExtension currentStep;

    public boolean debugBrowser = false;

//    public ScenarioStep rootScenarioStep;

    public CurrentScenarioState(TestCase testCase) {
        this.testCase = testCase;
        this.pickle = (Pickle) getProperty(testCase, "pickle");

    }

    private final LifecycleManager lifecycle = new LifecycleManager();

    public void startScenarioRun() {
        lifecycle.fire(Phase.BEFORE_SCENARIO_RUN);
        this.stepExtensions = (List<StepExtension>) getProperty(testCase, "stepExtensions");
        StepExtension rootScenarioStep = testCase.getRootScenarioStep();
        rootScenarioStep.setStepParsingMap(getParsingMap());
        startStep = stepExtensions.stream().filter(s -> s.debugStartStep).findFirst().orElse(null);
        if (startStep != null) {
            rootScenarioStep.childSteps.clear();
            StepExtension currentStep = startStep;
            while (currentStep != null) {
                rootScenarioStep.childSteps.add(currentStep);
                currentStep = (StepExtension) currentStep.nextSibling;
            }
            debugBrowser = true;
        }
        rootScenarioStep.runMethodDirectly = true;
        Pickle gherkinMessagesPickle = (Pickle) getProperty(testCase, "pickle");
        io.cucumber.messages.types.Pickle pickle = (io.cucumber.messages.types.Pickle) getProperty(gherkinMessagesPickle, "pickle");
        put("@@startFlag", "startFlag!");
//
        if (pickle.getValueRow() != null && !pickle.getValueRow().isEmpty()) {
            NodeMap examples = new NodeMap(MapConfigurations.MapType.EXAMPLE_MAP);
            examples.merge(pickle.getHeaderRow(), pickle.getValueRow());
            rootScenarioStep.getStepParsingMap().addMaps(examples);
        }

//        rootScenarioStep.addDefinitionFlag(DefinitionFlag.NO_LOGGING);
        testCaseState = getTestCaseState();
//        rootScenarioStep.runMethodDirectly = true;
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

        scenarioRunCleanUp();

        lifecycle.fire(Phase.AFTER_SCENARIO_CLEANUP);
    }

    public void scenarioRunCleanUp() {
        for (WebDriver driver : getScenarioWebDrivers()) {
            if (driver == null) continue;
            if (!debugBrowser) {
                try {
                    driver.quit();
                } catch (Exception ignored) {
                }
            }
        }
    }


    public void runStep(StepExtension stepExtension) {
        System.out.println("Running " + stepExtension);
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


    //Singleton registration of object in both step nodes, and local register.
    public static void registerScenarioObject(String key, Object value) {
        key = normalizeRegistryKey(key);
        GlobalState.getRunningStep().getStepNodeMap().put(key, value);
        GlobalRegistry.putLocal(key, value);
    }


    public static Object getScenarioObject(String key) {
        key = normalizeRegistryKey(key);
        Object returnObject =  GlobalState.getRunningStep().getStepParsingMap().get(key);
        return returnObject == null ? GlobalRegistry.getLocal(key) : returnObject;
    }

    public static String normalizeRegistryKey(String s) {
        if (s == null) return null;
        return s.strip()                       // remove leading/trailing whitespace
                .replaceAll("\\s+", " ")       // collapse consecutive whitespace
                .toLowerCase()                 // lowercase
                .replaceAll("[^a-z0-9._ ]", ""); // remove non-allowed chars
    }

}
