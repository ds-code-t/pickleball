package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.gherkin.GherkinDialects;
import io.cucumber.plugin.event.PickleStepTestStep;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.reporting.Report;
import tools.dscode.common.reporting.WorkBook;
import tools.dscode.common.reporting.logging.BaseConverter;

import java.nio.file.Path;
import java.util.Optional;


import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.cucumber.core.runner.CurrentScenarioState.currentScenarioState;
import static tools.dscode.common.domoperations.SeleniumUtils.waitMilliseconds;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.registry.GlobalRegistry.localOf;
import static tools.dscode.registry.GlobalRegistry.localOrGlobalOf;
import static tools.dscode.registry.GlobalRegistry.runners;

public class GlobalState {

    public static WorkBook defaultReport;

    // RowKey (scenario id) -> HTML report file
    private static final ConcurrentHashMap<String, Path> scenarioHtmlByRowKey = new ConcurrentHashMap<>();

    static {
        BaseConverter.setRowDataProvider(rowKey ->
                getReport()
                        .snapshotRow(rowKey)
                        .map(rs -> new BaseConverter.RowData(
                                rs.sheetName(),
                                rs.headers(),
                                rs.valuesByHeader()
                        ))
        );
    }

    /** Called internally by HTML converters to register their output and auto-link it into the XLSX. */
    public static void registerScenarioHtml(String rowKey, Path htmlFile) {
        if (rowKey == null || rowKey.isBlank() || htmlFile == null) return;

        scenarioHtmlByRowKey.put(rowKey, htmlFile);

        // Auto-add a hyperlink column into the default workbook.
        WorkBook wb = getReport();
        wb.put(rowKey, "Scenario HTML", new WorkBook.Link(htmlFile, "Open"));
    }

    public static Optional<Path> getScenarioHtml(String rowKey) {
        return Optional.ofNullable(scenarioHtmlByRowKey.get(rowKey));
    }

    public static final LifecycleManager lifecycle = new LifecycleManager();

    public static io.cucumber.core.runtime.Runtime getRuntime() {
        return localOrGlobalOf(io.cucumber.core.runtime.Runtime.class);
    }

    public static Runner globalRunner = null;

    public static Runner getGlobalRunner() {
        int counter = 0;
        while (globalRunner == null && counter++ < 10) {
            for(Runner runner : runners) {
                CachingGlue glue = (CachingGlue) getProperty(runner, "glue");
                Map stepDefinitionsByPattern = (Map) getProperty(glue, "stepDefinitionsByPattern");
                if(!stepDefinitionsByPattern.isEmpty())
                {
                    globalRunner = runner;
                    globalCachingGlue = glue;
                    break;
                }
            }
            waitMilliseconds(1000);
        }
        runners.clear();
        return globalRunner;
    }

    private static CachingGlue globalCachingGlue = null;

    public static CachingGlue getGlobalCachingGlue() {
        getGlobalRunner();
        return globalCachingGlue;
    }

//    public static io.cucumber.core.runner.Runner getRunner() {
//        return localOrGlobalOf(io.cucumber.core.runner.Runner.class);
//    }
//
//    public static io.cucumber.core.runner.Runner getLocalRunner() {

//        return getLocal("io.cucumber.core.runner.Runner");
//    }

//
//    public static Runner globalRunner = null;
//
//    public static CachingGlue globalGlue = null;
//
//    public static void setGlobalRunnerProperties(Runner runner) {
//        CachingGlue glue = (CachingGlue) getProperty(runner, "glue");
//        if (glue != null) {
//            Map<String, CoreStepDefinition> stepDefinitionsByPattern = (Map<String, CoreStepDefinition>) getProperty(glue, "stepDefinitionsByPattern");

//            if (!stepDefinitionsByPattern.isEmpty()) {

//                globalRunner = runner;
//                globalGlue = glue;
//            }
//        }
//    }

//    public static CachingGlue getLocalCachingGlue() {
//        return (CachingGlue) getProperty(getLocalRunner(), "glue");
//    }
//
//    public static CachingGlue getCachingGlue() {
//        return (CachingGlue) getProperty(getRunner(), "glue");
//    }




//    public static Locale locale;
//
//    public static Locale getLocal() {
//        if (locale == null) {
//            locale = (Locale) invokeAnyMethod(getRunner(), "localeForPickle", getGherkinMessagesPickle());
//        }
//        return locale;
//    }

    public static Options getGlobalOptions() {
        return (Options) getProperty(getGlobalRunner(), "runnerOptions");
    }

    public static EventBus getGlobalEventBus() {
        return (EventBus) getProperty(getGlobalRunner(), "bus");
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
        if (defaultReport == null) {
            defaultReport = getReport("reports/report.xlsx");
        }
        return defaultReport;
    }

    public static ConcurrentHashMap<String, WorkBook> workBookMap = new ConcurrentHashMap<>();

    public static WorkBook getReport(String reportPath) {
        if (reportPath == null || reportPath.isBlank())
            return getReport();
        return workBookMap.computeIfAbsent(reportPath, k -> new WorkBook(reportPath));
    }


}
