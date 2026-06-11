// file: tools/dscode/common/reporting/logging/Log.java
package tools.dscode.common.reporting.logging;

import tools.dscode.common.reporting.WorkBook;
import tools.dscode.common.reporting.logging.reportportal.ReportPortalBridge;
import tools.dscode.common.reporting.logging.simplehtml.SimpleHtmlReportConverter;


import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.cucumber.core.runner.GlobalState.getReport;
import static io.cucumber.core.runner.GlobalState.workBookMap;
import static tools.dscode.common.reporting.WorkBookConsolePrinter.printToConsole;
import static tools.dscode.common.reporting.logging.LogForwarder.logDebug;
import static tools.dscode.common.reporting.logging.LogForwarder.logWarn;


public final class Log {

    private static final Log GLOBAL = new Log();
    private final Set<BaseConverter> converters = ConcurrentHashMap.newKeySet();

    private Log() {
    }

    public static Log global() {
        return GLOBAL;
    }

    void register(BaseConverter converter) {
        converters.add(converter);
    }

    /**
     * Closes any converters that are still open (idempotent).
     */
    public void closeAll() {
        CleanupTrace.print("[closeAll] START");

        CleanupTrace.print("[closeAll] START: closing converters");
        for (BaseConverter c : converters) {
            String converterName = c == null ? "null" : c.getClass().getName();

            CleanupTrace.print("[closeAll] START: converter.close() - " + converterName);
            try {
                c.close();
                CleanupTrace.print("[closeAll] END: converter.close() - " + converterName);
            } catch (Throwable t) {
                CleanupTrace.printThrowable("[closeAll] THROWABLE: converter.close() - " + converterName, t);
                throw t;
            }
        }
        CleanupTrace.print("[closeAll] END: closing converters");

        CleanupTrace.print("[closeAll] START: printToConsole(getReport())");
        try {
            printToConsole(getReport());
            CleanupTrace.print("[closeAll] END: printToConsole(getReport())");
        } catch (Throwable t) {
            CleanupTrace.printThrowable("[closeAll] THROWABLE: printToConsole(getReport())", t);
            throw t;
        }

        CleanupTrace.print("[closeAll] START: writing workbook reports");
        for (WorkBook report : workBookMap.values()) {
            String outputFile = report == null ? "null" : String.valueOf(report.outputFile);

            CleanupTrace.print("[closeAll] START: report.write() - " + outputFile);
            try {
                report.write();
                CleanupTrace.print("[closeAll] END: report.write() - " + outputFile);
                logDebug("Report '" + report.outputFile + "' written successfully.");
            } catch (Exception e) {
                CleanupTrace.printThrowable("[closeAll] EXCEPTION: report.write() - " + outputFile, e);
                logWarn("Report '" + report.outputFile + "' failed to write due to: " + e.getMessage());
            }
        }
        CleanupTrace.print("[closeAll] END: writing workbook reports");

        CleanupTrace.print("[closeAll] START: ReportPortalBridge.finishLaunch(PASSED)");
        try {
            ReportPortalBridge.finishLaunch("PASSED");
            CleanupTrace.print("[closeAll] END: ReportPortalBridge.finishLaunch(PASSED)");
        } catch (Throwable t) {
            CleanupTrace.printThrowable("[closeAll] THROWABLE: ReportPortalBridge.finishLaunch(PASSED)", t);
            throw t;
        }

        CleanupTrace.print("[closeAll] START: SimpleHtmlReportConverter.writeFinalReport(reports/cucumber-report.html)");
        try {
            SimpleHtmlReportConverter.writeFinalReport(Path.of("reports/cucumber-report.html"));
            CleanupTrace.print("[closeAll] END: SimpleHtmlReportConverter.writeFinalReport(reports/cucumber-report.html)");
        } catch (Throwable t) {
            CleanupTrace.printThrowable(
                    "[closeAll] THROWABLE: SimpleHtmlReportConverter.writeFinalReport(reports/cucumber-report.html)",
                    t
            );
            throw t;
        }

        CleanupTrace.print("[closeAll] COMPLETE");
    }


}
