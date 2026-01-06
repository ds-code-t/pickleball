package tools.dscode.coredefinitions;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.When;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.reporting.Report;
import tools.dscode.common.reporting.WorkBook;


import java.sql.SQLOutput;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;


import static io.cucumber.core.runner.GlobalState.enterInReport;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getReport;
import static io.cucumber.core.runner.GlobalState.initializeReport;
import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;

public class ReportingSteps {


    @When("^Enter (.*) in the \"(.+)\" column(?: in the \"(.+)\" row)?(?: in the \"(.+)\" sheet)?(?: in the \"(.+)\" report)?$")
    public void setObject(String value, String columnName, String rowKey, String sheetName, String reportName) {
        enterInReport(reportName, sheetName, rowKey, columnName, createValueWrapper(value));
    }


    @When("^Initialize (?:\"(.+)\" )?report headers(?: in \"(.+)\" sheet)?(?: sort \"(.+)\" (numerically|alphabetically)(?: (ascending|descending)?)?)?$")
    public void initializeReportStep(String reportPath, String sheetName, String sortHeader, String sortKind, String sortDirection, List<String> headers) {
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
    public void setRow(String reportPath, String sheetName, DataTable dataTable) {

        Report report =  getReport(reportPathPreProcess(reportPath));
        if(sheetName != null && !sheetName.isBlank())
            report = report.sheet(sheetName);
        List<List<String>> lists = dataTable.asLists();
        String rowKey = getCurrentScenarioState().id.toString();

        for(int i = 0; i < lists.getFirst().size();   i++)
        {
            String header = lists.getFirst().get(i);
            ValueWrapper value = createValueWrapper(lists.get(1).get(i));
            System.out.println("@@header: " + header);
            System.out.println("@@value: " + value);
            System.out.println("@@value asBestGuessXlsxValue: " + value.asBestGuessXlsxValue());
            System.out.println("@@value asBestGuessXlsxValue: " + (value.asBestGuessXlsxValue() == null ? "null" :value.asBestGuessXlsxValue().getClass()));
            System.out.println("@@value getValue: " + value.getValue());
            report.put(rowKey, header , value.asBestGuessXlsxValue());
        }
    }

    public static String reportPathPreProcess(String reportPath)
    {
        if(reportPath == null || reportPath.isBlank())
            return null;
        if(!reportPath.endsWith(".xlsx"))
            reportPath += ".xlsx";
        if(!reportPath.contains("/"))
            return  "reports/" + reportPath;
        return reportPath;
    }

}
