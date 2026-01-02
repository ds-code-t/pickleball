package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.gherkin.GherkinDialects;
import io.cucumber.plugin.event.PickleStepTestStep;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.reporting.Report;
import tools.dscode.common.reporting.WorkBook;


import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static io.cucumber.core.runner.CurrentScenarioState.currentScenarioState;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.registry.GlobalRegistry.localOrGlobalOf;

public class GlobalState {
    public static final LifecycleManager lifecycle = new LifecycleManager();

    public static io.cucumber.core.runtime.Runtime getRuntime() {
        return localOrGlobalOf(io.cucumber.core.runtime.Runtime.class);
    }

    public static io.cucumber.core.runner.Runner getRunner() {
        return localOrGlobalOf(io.cucumber.core.runner.Runner.class);
    }

    public static CachingGlue getCachingGlue() {
        return (CachingGlue) getProperty(getRunner(), "glue");
    }

    public static Options getOptions() {
        return (Options) getProperty(getRunner(), "runnerOptions");
    }

    public static EventBus getEventBus() {
        return (EventBus) getProperty(getRunner(), "bus");
    }

    public static io.cucumber.core.gherkin.Pickle getPickleFromPickleTestStep(PickleStepTestStep pickleStepTestStep) {
        return (io.cucumber.core.gherkin.Pickle) getProperty(pickleStepTestStep, "pickle");
    }

    public static io.cucumber.core.gherkin.Pickle getGherkinMessagesPickle() {
        return (io.cucumber.core.gherkin.Pickle) getProperty(getTestCase(), "pickle");
    }

    public static String language = "en";

    public static String getLanguage() {
        return language;
    }

    public static GherkinDialect getGherkinDialect() {
        return GherkinDialects.getDialect(language).orElse(GherkinDialects.getDialect("en").get());
    }

    public static String getGivenKeyword() {
        return getGherkinDialect().getGivenKeywords().getFirst();
    }

    public static io.cucumber.core.runner.TestCase getTestCase() {
        return localOrGlobalOf(io.cucumber.core.runner.TestCase.class);
    }

    public static io.cucumber.core.runner.TestCaseState getTestCaseState() {
        return localOrGlobalOf(io.cucumber.core.runner.TestCaseState.class);
    }

    public static io.cucumber.core.runner.CurrentScenarioState getCurrentScenarioState() {
        return currentScenarioState.get();
//        return (CurrentScenarioState) getProperty(localOrGlobalOf(TestCase.class), "currentScenarioState");
    }

    public static StepExtension getRunningStep() {
        return getCurrentScenarioState().getCurrentStep();
    }

    public static void enterInDefaultReport(String columnName, Object value) {
        enterInReport(null, null, null, columnName, value);
    }

    public static void enterInDefaultReport(String sheetName, String columnName, Object value) {
        enterInReport(null, sheetName, null, columnName, value);
    }


    public static void enterInReport(
            String reportPath,
            String sheetName,
            String rowKey,
            String columnName,
            Object value
    ) {
        Report target = getReport(reportPath); // guaranteed non-null
        if (sheetName != null && !sheetName.isBlank()) target = target.sheet(sheetName);

        String effectiveRowKey = (rowKey == null || rowKey.isBlank())
                ? getCurrentScenarioState().id.toString()
                : rowKey;

        target.put(effectiveRowKey, columnName, value);
    }

    public static void initializeReport(
            String reportPath,
            String sheetName,
            List<String> headers,
            String sortByColumn,
            WorkBook.SortKind kind,
            WorkBook.SortDirection direction
    ) {
        Report target = getReport(reportPath); // guaranteed non-null
        if (sheetName != null && !sheetName.isBlank()) target = target.sheet(sheetName);

        if (headers != null && !headers.isEmpty()) {
            target.setHeaderOrder(headers);
        }

        if (sortByColumn != null && !sortByColumn.isBlank()) {
            target.sortRowsByColumn(
                    sortByColumn,
                    (kind != null) ? kind : WorkBook.SortKind.ALPHABETIC,
                    (direction != null) ? direction : WorkBook.SortDirection.ASC
            );
        }
    }





    public static WorkBook getReport() {
        CurrentScenarioState currentScenarioState = getCurrentScenarioState();
        if (currentScenarioState.defaultReport == null) {
            currentScenarioState.defaultReport = getReport("reports/report.xlsx");
        }
        return currentScenarioState.defaultReport;
    }

    public static ConcurrentHashMap<String, WorkBook> workBookMap = new ConcurrentHashMap<>();

    public static WorkBook getReport(String reportPath) {
        if(reportPath == null || reportPath.isBlank())
            return getReport();
        return workBookMap.computeIfAbsent(reportPath, k -> new WorkBook(reportPath));
    }


}
