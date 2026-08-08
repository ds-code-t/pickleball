package tools.dscode.common.reporting.diagnostic;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.datatable.DataTable;
import tools.dscode.common.reporting.WorkBook;
import tools.dscode.common.reporting.logging.Log;
import tools.dscode.common.reporting.logging.simplehtml.SimpleHtmlReportConverter;
import tools.dscode.coredefinitions.ReportingSteps;

import java.nio.file.Path;

public privileged aspect ReportRetentionAspect {
    void around(SimpleHtmlReportConverter converter):
            execution(protected void tools.dscode.common.reporting.logging.simplehtml.SimpleHtmlReportConverter.onClose())
            && this(converter) {
        if (DiagnosticRuntime.isDiagnostic()) {
            converter.scopes.clear();
            return;
        }
        CurrentScenarioState state = io.cucumber.core.runner.GlobalState.getCurrentScenarioState();
        boolean failed = state != null && state.isScenarioFailed();
        if (ReportRetentionPolicy.writeAutomaticScenarioFiles(failed, false)) {
            proceed(converter);
        } else {
            converter.scopes.clear();
        }
    }

    void around(): execution(public static void tools.dscode.common.reporting.logging.simplehtml.SimpleHtmlReportConverter.writeFinalReport()) {
        if (!DiagnosticRuntime.isDiagnostic() && ReportRetentionPolicy.writeAutomaticRunFiles()) proceed();
    }

    void around(Path path):
            execution(public static void tools.dscode.common.reporting.logging.simplehtml.SimpleHtmlReportConverter.writeFinalReport(java.nio.file.Path))
            && args(path) {
        if (!DiagnosticRuntime.isDiagnostic() && ReportRetentionPolicy.writeAutomaticRunFiles()) proceed(path);
    }

    void around() throws java.io.IOException:
            call(void tools.dscode.common.reporting.WorkBook.write())
            && withincode(void tools.dscode.common.reporting.logging.Log.closeAll()) {
        WorkBook report = (WorkBook) thisJoinPoint.getTarget();
        if (ExplicitReportRegistry.isExplicit(report) || ReportRetentionPolicy.writeAutomaticRunFiles()) proceed();
    }

    void around(): execution(public void tools.dscode.common.reporting.logging.Log.closeAll()) {
        if (!DiagnosticRuntime.isDiagnostic()) {
            proceed();
            return;
        }
        ExplicitReportRegistry.writeExplicit(io.cucumber.core.runner.GlobalState.workBookMap.values());
        DiagnosticRuntime.finishRun();
    }

    void around(String reportPath, String sheetName, java.util.List lists):
            call(void tools.dscode.coredefinitions.ReportingSteps.setRow(String, String, java.util.List))
            && withincode(void io.cucumber.core.runner.CurrentScenarioState.startScenarioRun())
            && args(reportPath, sheetName, lists) {
        if (!DiagnosticRuntime.isDiagnostic()) proceed(reportPath, sheetName, lists);
    }

    before(String reportPath, String sheetName, String sortHeader, String sortKind, String sortDirection, java.util.List headers):
            execution(public static void tools.dscode.coredefinitions.ReportingSteps.initializeReportStep(String, String, String, String, String, java.util.List))
            && args(reportPath, sheetName, sortHeader, sortKind, sortDirection, headers) {
        ExplicitReportRegistry.mark(reportPath);
    }

    before(String value, String columnName, String rowKey, String sheetName, String reportName):
            execution(public static void tools.dscode.coredefinitions.ReportingSteps.setObject(String, String, String, String, String))
            && args(value, columnName, rowKey, sheetName, reportName) {
        ExplicitReportRegistry.mark(reportName);
    }

    before(String reportPath, String sheetName, DataTable dataTable):
            execution(public static void tools.dscode.coredefinitions.ReportingSteps.setRow(String, String, io.cucumber.datatable.DataTable))
            && args(reportPath, sheetName, dataTable) {
        ExplicitReportRegistry.mark(reportPath);
    }
}
