package tools.dscode.coredefinitions;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.When;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.reporting.Report;
import tools.dscode.common.reporting.WorkBook;
import tools.dscode.common.reporting.logging.Entry;


import java.sql.SQLOutput;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;


import static io.cucumber.core.runner.CurrentScenarioState.getScenarioLogRoot;
import static io.cucumber.core.runner.CurrentScenarioState.logToScenario;
import static io.cucumber.core.runner.GlobalState.enterInReport;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getReport;
import static io.cucumber.core.runner.GlobalState.initializeReport;
import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;

public class ReportingSteps {


    @When("^Enter (.*) in the \"(.+)\" column(?: in the \"(.+)\" row)?(?: in the \"(.+)\" sheet)?(?: in the \"(.+)\" report)?$")
    public static void setObject(String value, String columnName, String rowKey, String sheetName, String reportName) {
        enterInReport(reportName, sheetName, rowKey, columnName, createValueWrapper(value));
    }


    @When("^Initialize (?:\"(.+)\" )?report headers(?: in \"(.+)\" sheet)?(?: sort \"(.+)\" (numerically|alphabetically)(?: (ascending|descending)?)?)?$")
    public static void initializeReportStep(String reportPath, String sheetName, String sortHeader, String sortKind, String sortDirection, List<String> headers) {
        WorkBook.SortKind sortKindEnum = sortKind == null || sortKind.isBlank() ? null : WorkBook.SortKind.valueOf(sortKind.trim().toUpperCase(Locale.ROOT));
        WorkBook.SortDirection sortDirectionEnum = sortDirection == null || sortDirection.isBlank() ? null : WorkBook.SortDirection.valueOf(sortDirection.trim().toUpperCase(Locale.ROOT));
        initializeReport(
                reportPath,
                sheetName,
                headers,
                sortHeader,
                sortKindEnum,
                sortDirectionEnum
        );
    }

    @When("^Set (?:\"(.+)\" )?report values(?: in \"(.+)\" sheet)?$")
    public static void setRow(String reportPath, String sheetName, DataTable dataTable) {
        setRow(reportPath, sheetName, dataTable.asLists());
    }

    public static void setRow(String reportPath, String sheetName, List<List<String>> lists) {
        Report report = getReport(reportPathPreProcess(reportPath));
        if (sheetName != null && !sheetName.isBlank())
            report = report.sheet(sheetName);

        String rowKey = getCurrentScenarioState().id.toString();

        for (int i = 0; i < lists.getFirst().size(); i++) {
            String header = lists.getFirst().get(i);
            ValueWrapper value = createValueWrapper(lists.get(1).get(i));
            report.put(rowKey, header, value.asBestGuessXlsxValue());
        }
    }

    public static String reportPathPreProcess(String reportPath) {
        if (reportPath == null || reportPath.isBlank())
            return null;
        if (!reportPath.endsWith(".xlsx"))
            reportPath += ".xlsx";
        if (!reportPath.contains("/"))
            return "reports/" + reportPath;
        return reportPath;
    }


    @When("^Scenario Log: (.*)$")
    public static void logEntry(String logEntry, DataTable dataTable) {
        Entry entry = logToScenario(logEntry, dataTable).timestamp();
    }
}
