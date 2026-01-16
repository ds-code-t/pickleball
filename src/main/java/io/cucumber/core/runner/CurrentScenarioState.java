package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.datatable.DataTable;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ScenarioMapping;
import tools.dscode.common.reporting.WorkBook;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.extentreporting.ExtentReportConverter;
import tools.dscode.common.status.SoftExceptionInterface;
import tools.dscode.common.status.SoftRuntimeException;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.preparsing.ParsedLine;
import tools.dscode.registry.GlobalRegistry;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getTestCaseState;
import static io.cucumber.core.runner.GlobalState.lifecycle;
import static tools.dscode.common.GlobalConstants.ALWAYS_RUN;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_HARD_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_PASSING;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_SOFT_FAILED;
import static tools.dscode.common.annotations.DefinitionFlag.IGNORE_CHILDREN_IF_FALSE;
import static tools.dscode.common.annotations.DefinitionFlag.IGNORE_CHILDREN;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.StringUtilities.safeFileName;
import static tools.dscode.registry.GlobalRegistry.LOCAL;
import static tools.dscode.registry.GlobalRegistry.getScenarioWebDrivers;

public class CurrentScenarioState extends ScenarioMapping {

    public final UUID id = UUID.randomUUID();

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

    WorkBook defaultReport;


//    public ScenarioStep rootScenarioStep;

    public static final ThreadLocal<CurrentScenarioState> currentScenarioState = new ThreadLocal<>();

    public CurrentScenarioState(TestCase testCase) {
        LOCAL.set(new ConcurrentHashMap<>());
        GlobalRegistry.putLocal(testCase.getClass().getCanonicalName(), testCase);
        this.testCase = testCase;
        this.pickle = (Pickle) getProperty(testCase, "pickle");
    }
    private Entry scenarioLog;

    public static Entry getScenarioLogRoot() {
        return getCurrentScenarioState().scenarioLog;
    }

    public static Entry logToScenario(String message) {
        return logToScenario(message, null);
    }
    public static Entry logToScenario(String message, DataTable dataTable) {
        Entry entry = getCurrentScenarioState().scenarioLog.logInfo(message);
        if (dataTable == null) return entry.timestamp();
        List<List<String>> lists = dataTable.asLists();
        if (lists.isEmpty()) return entry.timestamp();
        if (lists.size() == 1){
            lists.getFirst().forEach(entry::tag);
            return entry.timestamp();
        }
        entry.fields.putAll(dataTable.asMap());
        return entry.timestamp();
    }

    public void startScenarioRun() {
        String scenarioName = pickle.getName() + " , Line " + pickle.getLocation().getLine();
        System.out.println("Starting scenario: '" + scenarioName + "'");
        scenarioLog =
                Entry.of(scenarioName)
                        .tag("SCENARIO")
                        .on(new ExtentReportConverter(
                                Path.of("reports/extent",  safeFileName(scenarioName + ".html"))
                        ))
                        .start();



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
            throw t;
        }
        getScenarioLogRoot().stop().close();

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

        currentStep = stepExtension;


        stepExtension.lineData = new ParsedLine(stepExtension.getUnmodifiedText());
        StepBase stepBase = stepExtension;
        while (true) {
            stepBase = stepBase.parentStep;
            if (stepBase == null) {
                stepExtension.inheritedLineData = new ParsedLine();
                break;
            }

            if (stepBase.lineData != null) {


                stepExtension.inheritedLineData = stepBase.lineData.clone();


                break;
            }
        }

//        stepExtension.lineData = new ParsedLine(stepExtension.getUnmodifiedText());
        if (stepExtension.inheritedLineData != null) {
            stepExtension.lineData.inheritedContextPhrases.addAll(stepExtension.inheritedLineData.inheritedContextPhrases);
        }


        if ((stepExtension.parentStep != null && stepExtension.parentStep.logAndIgnore) || stepExtension.inheritedLineData.lineConditionalMode < 1) {
            stepExtension.logAndIgnore = true;
        }
//        else if (stepExtension.isDynamicStep) {


//            String dynamicStepText = stepExtension.pickleStepTestStep.getStepText().toLowerCase().replaceAll(",|then|the|and|or|:", "").strip();
//            if (dynamicStepText.startsWith("else") && !dynamicStepText.equals("else")) {


//            if (stepExtension.pickleStepTestStep.getStepText().toLowerCase().replaceAll(",|then|the|and|or", "").strip().startsWith("else")) {
//                if (stepExtension.previousSibling == null || stepExtension.previousSibling.lineData.lineConditionalMode > -1) {
//                    stepExtension.logAndIgnore = true;
//                }
//            }
//        }

        runningStep(stepExtension);
    }


    public void runningStep(StepExtension stepExtension) {
        System.out.println("Running " + stepExtension);
        if (!shouldRun(stepExtension)) {
            stepExtension.skipped = true;
            if (stepExtension.nextSibling != null) {
                runStep((StepExtension) stepExtension.nextSibling);
            }
            return;
        }

        Result result;
        if (stepExtension.runMethodDirectly) {
            stepExtension.runPickleStepDefinitionMatch();
            result = new Result(Status.PASSED, Duration.ZERO, null);
        } else {
            result = stepExtension.run();
        }


        io.cucumber.plugin.event.Status status = result.getStatus();
        Throwable throwable = result.getError();


        if (!result.getStatus().equals(Status.PASSED) && !result.getStatus().equals(Status.SKIPPED) && throwable == null) {

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
                isScenarioComplete = true;
            }
            scenarioLog.fail("SCENARIO FAILED: " + throwable.getMessage());
        }


//        if (isScenarioComplete())
//            return;


        for (StepBase attachedStep : stepExtension.attachedSteps) {
            runStep((StepExtension) attachedStep);
        }


        if (stepExtension.logAndIgnore && stepExtension.definitionFlags.contains(IGNORE_CHILDREN_IF_FALSE)) {
            return;
        }


        if (!stepExtension.definitionFlags.contains(IGNORE_CHILDREN)) {
            List<StepExtension> repeatSteps = getNestedRepetitions(stepExtension);
            for (StepExtension repeatStep : repeatSteps) {
                StepExtension firstChild = (StepExtension) repeatStep.initializeChildSteps();

                if (firstChild != null) {
                    runStep(firstChild);

                }
            }
        }

        if (stepExtension.nextSibling != null) {
            runStep((StepExtension) stepExtension.nextSibling);
        }
    }

    public List<StepExtension> getNestedRepetitions(StepExtension stepExtension) {
        List<StepExtension> returnList = new ArrayList<>();

        List<PhraseData> branchedPhrases = null;
        try {
            branchedPhrases = stepExtension.lineData.inheritedContextPhrases.getLast().getLast().branchedPhrases;
        } catch (Throwable ignored) {
            returnList.add(stepExtension);
            return returnList;
        }

        for (PhraseData branch : branchedPhrases) {
            try {
                StepExtension branchStep = (StepExtension) stepExtension.clone();
                branchStep.lineData.inheritedContextPhrases.getLast().add(branch);
                returnList.add(branchStep);
            } catch (Throwable t) {
                t.printStackTrace();
                throw new RuntimeException(t);
            }
        }

        if (branchedPhrases.isEmpty()) {
            returnList.add(stepExtension);
        }
        return returnList;
    }


    private boolean isScenarioHardFail = false;
    private boolean isScenarioSoftFail = false;
    private boolean isScenarioComplete = false;

    public static void endScenario() {
        getCurrentScenarioState().isScenarioComplete = true;
    }

    public static void failScenario(String failMessage) {
        if (failMessage == null || failMessage.isBlank())
            failMessage = "Manually Hard Failed Scenario";
        throw new RuntimeException(failMessage.trim());
    }

    public static void softFailScenario(String failMessage) {
        if (failMessage == null || failMessage.isBlank())
            failMessage = "Manually Soft Failed Scenario";
        throw new SoftRuntimeException(failMessage.trim());
    }

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

//        return !stepExtension.skipped;
        return !isScenarioComplete();
    }


    //Singleton registration of object in both step nodes, and local register.
    public static void registerScenarioObject(String key, Object value) {
        key = normalizeRegistryKey(key);
        GlobalState.getRunningStep().getStepNodeMap().put(key, value);
        GlobalRegistry.putLocal(key, value);
    }


    public static Object getScenarioObject(String key) {
        key = normalizeRegistryKey(key);
        Object returnObject = GlobalState.getRunningStep().getStepParsingMap().get(key);
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
