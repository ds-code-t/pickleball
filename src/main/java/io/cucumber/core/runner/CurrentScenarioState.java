package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.stepexpression.StepExpressionFactory;
import io.cucumber.datatable.DataTable;
import io.cucumber.datatable.DataTableTypeRegistryTableConverter;
import io.cucumber.docstring.DocStringTypeRegistryDocStringConverter;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ScenarioMapping;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.reportportal.ReportPortalBridgeConverter;
import tools.dscode.common.reporting.logging.simplehtml.SimpleHtmlReportConverter;
import tools.dscode.common.exceptions.SoftExceptionInterface;
import tools.dscode.common.exceptions.SoftRuntimeException;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.preparsing.ParsedLine;
import tools.dscode.coredefinitions.ReportingSteps;
import tools.dscode.registry.GlobalRegistry;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getTestCaseState;
import static io.cucumber.core.runner.GlobalState.lifecycle;
import static io.cucumber.core.runner.GlobalState.pickleballLog;
import static io.cucumber.core.runner.StepLogic.stepCloner;
import static org.junit.jupiter.api.Assertions.fail;
import static tools.dscode.common.GlobalConstants.ALWAYS_RUN;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_HARD_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_PASSING;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_SOFT_FAILED;
import static tools.dscode.common.annotations.DefinitionFlag.IGNORE_CHILDREN_IF_FALSE;
import static tools.dscode.common.annotations.DefinitionFlag.IGNORE_CHILDREN;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.StringUtilities.safeFileName;
import static tools.dscode.common.variables.RunVars.getDependencyProperty;
import static tools.dscode.common.variables.RunVars.getDependencyPropertyBoolean;
import static tools.dscode.registry.GlobalRegistry.LOCAL;
import static tools.dscode.registry.GlobalRegistry.getScenarioWebDrivers;

public class CurrentScenarioState extends ScenarioMapping {
    public boolean endCurrentScenario;

    public List<Throwable> stepFailures = new ArrayList<>();

    public final UUID id = UUID.randomUUID();

    public final TestCase testCase;
    public final Pickle pickle;
    public CachingGlue cachingGlue;
    public Runner scenarioRunner;

    List<StepExtension> stepExtensions;
    StepExtension startStep;
    private TestCaseState testCaseState;

    public StepExtension getCurrentStep() {
        return currentStep;
    }

    private StepExtension currentStep;
    public Phrase currentPhrase;

    public boolean debugBrowser = false;

    public static final ThreadLocal<CurrentScenarioState> currentScenarioState = new ThreadLocal<>();

    public final String scenarioName;
    public final String scenarioNameAndLine;


    public CurrentScenarioState(TestCase testCase) {
        LOCAL.set(new ConcurrentHashMap<>());
        GlobalRegistry.putLocal(testCase.getClass().getCanonicalName(), testCase);
        this.testCase = testCase;
        this.pickle = (Pickle) getProperty(testCase, "pickle");
        scenarioName = pickle.getName();
        scenarioNameAndLine = scenarioName + " , Line " + pickle.getLocation().getLine();
    }


    public static CachingGlue getGlue() {
        return getCurrentScenarioState().cachingGlue;
    }

    public static StepExpressionFactory getStepExpressionFactory() {
        return (StepExpressionFactory) getProperty(getCurrentScenarioState().cachingGlue, "stepExpressionFactory");
    }

    public static DocStringTypeRegistryDocStringConverter getDocStringTypeRegistryDocStringConverter() {
        return (DocStringTypeRegistryDocStringConverter) getProperty(getStepExpressionFactory(), "docStringConverter");
    }

    public static DataTableTypeRegistryTableConverter getDataTableTypeRegistryTableConverter() {
        return (DataTableTypeRegistryTableConverter) getProperty(getStepExpressionFactory(), "tableConverter");
    }

    public static Runner getRunner() {
        return getCurrentScenarioState().scenarioRunner;
    }

    public Entry scenarioLog;

    public static Entry getScenarioLogRoot() {
        return getCurrentScenarioState().scenarioLog;
    }

    public static Entry logToScenario(String message) {
        return logToScenario(message, null);
    }

    public static Entry logToScenario(String message, DataTable dataTable) {
        Entry entry = getCurrentScenarioState().scenarioLog.info(message);
        if (dataTable == null) return entry.timestamp();
        List<List<String>> lists = dataTable.asLists();
        if (lists.isEmpty()) return entry.timestamp();
        if (lists.size() == 1) {
            lists.getFirst().forEach(entry::tag);
            return entry.timestamp();
        }
        entry.fields.putAll(dataTable.asMap());
        return entry.timestamp();
    }

//    public static boolean logToReportPortal = getDependencyPropertyBoolean("rp.enable");

    public void startScenarioRun() {
        String scenarioName = pickle.getName() + " , Line " + pickle.getLocation().getLine();
        pickleballLog.info("Starting scenario: '" + scenarioName + "'");
        scenarioLog =
                Entry.of(scenarioName)
                        .tag("SCENARIO")
                        .tag("RP_NEST:Scenarios_root")
                        .on(new SimpleHtmlReportConverter(
                                Path.of("reports/tests", safeFileName(scenarioName + ".html"))
                        ))
                        .on(new ReportPortalBridgeConverter());

//        if(logToReportPortal)
//            scenarioLog.on(new ReportPortalBridgeConverter());

        scenarioLog.start();


        lifecycle.fire(Phase.BEFORE_SCENARIO_RUN);

        ReportingSteps.setRow(null, null, List.of(
                List.of("Scenario Name", "Line", "STATUS", "INFO"),
                List.of(pickle.getName(), String.valueOf(pickle.getLocation().getLine()), "", "Started")));

        this.stepExtensions = (List<StepExtension>) getProperty(testCase, "stepExtensions");
        StepExtension rootScenarioStep = testCase.getRootScenarioStep();

//        StepExtension rootScenarioStep =
//                retry(4, 1_000, testCase::getRootScenarioStep);


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
        Throwable throwable = null;
        try {

            runStep(rootScenarioStep);

            if (isScenarioFailed())
                lifecycle.fire(Phase.AFTER_SCENARIO_FAIL);
            else
                lifecycle.fire(Phase.AFTER_SCENARIO_PASS);

        } catch (Throwable t) {
            lifecycle.fire(Phase.AFTER_SCENARIO_FAIL);
            throwable = t;
        }
        getScenarioLogRoot().stop().close();

        lifecycle.fire(Phase.AFTER_SCENARIO_RUN);

        String infoMessage = stepFailures.isEmpty() ? "COMPLETED" : stepFailures.stream()
                .flatMap(t -> Stream.iterate(t, Objects::nonNull, Throwable::getCause))
                .map(Throwable::getMessage)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(", "));


        ReportingSteps.setRow(null, null, List.of(
                List.of("STATUS", "INFO"),
                List.of((isScenarioFailed() ? "FAILED" : "PASSED"), infoMessage)));

        scenarioRunCleanUp();
        lifecycle.fire(Phase.AFTER_SCENARIO_CLEANUP);

        if (throwable != null)
            throw new RuntimeException(throwable);
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
        if (endCurrentScenario)
            return;
        currentStep = stepExtension;
        stepExtension.lineData = new ParsedLine(stepExtension.getUnmodifiedText());
        stepExtension.lineData.setInheritance(stepExtension);
        currentPhrase = (Phrase) stepExtension.lineData.inheritedPhrase;
        runningStep(stepExtension);
        if (stepExtension instanceof ScenarioStep)
            endCurrentScenario = false;
    }


    public void runningStep(StepExtension stepExtension) {
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

        if (!stepExtension.lineData.inheritancePhrases.isEmpty())
            stepExtension.inheritancePhrase = stepExtension.lineData.inheritancePhrases.getFirst();


        Status status = result.getStatus();
        Throwable throwable = result.getError();

        if (!result.getStatus().equals(Status.PASSED) && !result.getStatus().equals(Status.SKIPPED) && throwable == null) {

            if (status.equals(Status.UNDEFINED))
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
            stepFailures.add(throwable);
            scenarioLog.fail("SCENARIO FAILED: " + throwable.getMessage());
        }


//        if (isScenarioComplete())
//            return;
        if (!stepExtension.replacementSteps.isEmpty()) {
            StepBase last = null;
            int insertionCount = stepExtension.parentStep.childSteps.indexOf(stepExtension);
            for (StepBase replacementStep : stepExtension.replacementSteps) {
                insertionCount++;
                stepExtension.parentStep.childSteps.add(insertionCount, replacementStep);
                if (last == null) {
                    replacementStep.previousSibling = stepExtension.previousSibling;
                } else {
                    last.nextSibling = replacementStep;
                    replacementStep.previousSibling = last;
                }
                last = replacementStep;
            }
            last.nextSibling = stepExtension.nextSibling;
            stepExtension.nextSibling = stepExtension.replacementSteps.getFirst();
            last.childSteps.addAll(stepExtension.childSteps);
            stepExtension.childSteps.clear();
            stepExtension.parentStep.childSteps.remove(stepExtension);
        }


        for (StepBase attachedStep : stepExtension.attachedSteps) {
            runStep((StepExtension) attachedStep);
        }


        if (stepExtension.logAndIgnore && stepExtension.definitionFlags.contains(IGNORE_CHILDREN_IF_FALSE)) {
            return;
        }


        if (!stepExtension.definitionFlags.contains(IGNORE_CHILDREN)) {
            if (stepExtension.lineData.inheritancePhrases.isEmpty())
                stepExtension.lineData.inheritancePhrases.add(null);
            for (PhraseData inheritancePhrase : stepExtension.lineData.inheritancePhrases) {
                if (inheritancePhrase != null && inheritancePhrase.repeatRootPhrase) {
                    while (true) {
                        PhraseData clonedChainStart = inheritancePhrase.cloneRepeatedChain();
                        clonedChainStart.parsedLine.runPhraseFromLine(clonedChainStart);
                        if (clonedChainStart.phraseConditionalMode < 1) break;
                        StepExtension firstChild = (StepExtension) ((StepExtension) stepExtension.clone(inheritancePhrase)).initializeChildSteps();
                        if (firstChild != null) {
                            runStep(firstChild);
                        }
                    }
                } else {
                    List<StepExtension> clonedSteps = stepCloner(inheritancePhrase, stepExtension);
                    for (StepExtension repeatStep : clonedSteps) {
                        StepExtension firstChild = (StepExtension) repeatStep.initializeChildSteps();
                        if (firstChild != null) {
                            runStep(firstChild);
                        }
                    }
                }
            }
        }


        if (stepExtension.nextSibling != null) {
            runStep((StepExtension) stepExtension.nextSibling);
        }
    }


    private boolean isScenarioHardFail = false;
    private boolean isScenarioSoftFail = false;
    private boolean isScenarioComplete = false;

    public static void endTest() {
        getCurrentScenarioState().isScenarioComplete = true;
    }

    public static void failScenario(String failMessage) {
        if (failMessage == null || failMessage.isBlank())
            failMessage = "Manually Hard Failed Scenario";
        fail(failMessage.trim());

//        throw new AssertionFailedError(failMessage.trim());
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
        GlobalState.getRunningStep().getDefaultStepNodeMap().put(key, value);
        GlobalRegistry.putLocal(key, value);
    }


    public static Object getScenarioObject(String key) {
        key = normalizeRegistryKey(key);
        Object returnObject = GlobalState.getRunningStep().getStepParsingMap().get(key);
        return returnObject == null ? GlobalRegistry.getLocal(key) : returnObject;
    }

    public static final String normalizationPrefix = "_obj_";

    public static String normalizeRegistryKey(String s) {
        if (s == null) return null;
        if (s.startsWith("_"))
            return s;
        String returnString = s.strip()                       // remove leading/trailing whitespace
                .replaceAll("\\s+", " ")       // collapse consecutive whitespace
                .toLowerCase()                 // lowercase
                .replaceAll("[^a-z0-9._ ]", ""); // remove non-allowed chars
        return returnString.startsWith(normalizationPrefix) ? returnString : normalizationPrefix + returnString;
    }

}
