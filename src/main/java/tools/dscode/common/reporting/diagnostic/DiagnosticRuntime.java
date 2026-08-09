package tools.dscode.common.reporting.diagnostic;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.plugin.event.Result;
import io.cucumber.datatable.DataTable;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.Level;
import tools.dscode.testengine.PickleballRunner;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static tools.dscode.testengine.PKB_props.PKB_REPORTING_MODE;
import static tools.dscode.testengine.PKB_props.PKB_REPORT_RETENTION;

public final class DiagnosticRuntime {
    public static volatile boolean DIAGNOSTIC_MODE = false;

    private static volatile DiagnosticReporter reporter;

    private DiagnosticRuntime() {
    }

    public static synchronized void configure(Map<String, String> values) {
        DIAGNOSTIC_MODE = "diagnostic".equalsIgnoreCase(find(values, PKB_REPORTING_MODE));
        ReportRetentionPolicy.configure(find(values, PKB_REPORT_RETENTION));
        if (DIAGNOSTIC_MODE && (reporter == null || reporter.isFinished())) {
            reporter = new DiagnosticReporter(values, PickleballRunner.isDirectRunProfileActive());
        }
    }

    public static boolean isDiagnostic() {
        return DIAGNOSTIC_MODE;
    }

    public static void startScenario(CurrentScenarioState state) {
        if (!DIAGNOSTIC_MODE || reporter == null) return;
        reporter.startScenario(state);
    }

    public static void endScenario(CurrentScenarioState state, boolean interrupted, Throwable error) {
        if (!DIAGNOSTIC_MODE || reporter == null) return;
        reporter.endScenario(state, interrupted, error);
    }

    public static void recordEntry(Entry entry, String phase) {
        if (DIAGNOSTIC_MODE && reporter != null) reporter.recordEntry(entry, phase);
    }

    public static void recordFilteredLog(Level level, String message) {
        if (DIAGNOSTIC_MODE && reporter != null) reporter.recordFilteredLog(level, message);
    }

    public static void beginStep(StepExtension step) {
        if (DIAGNOSTIC_MODE && reporter != null) reporter.beginStep(step);
    }

    public static void bindStepDefinition(StepExtension step) {
        if (DIAGNOSTIC_MODE && reporter != null) reporter.bindStepDefinition(step);
    }

    public static void endStep(StepExtension step, Result result, Throwable error) {
        if (DIAGNOSTIC_MODE && reporter != null) reporter.endStep(step, result, error);
    }

    public static void observeCapability(String capability) {
        if (DIAGNOSTIC_MODE && reporter != null) reporter.observeCapability(capability);
    }

    public static void observeRunType(String inlineRunType, DataTable dataTable) {
        if (!DIAGNOSTIC_MODE || reporter == null) return;
        observeRunTypeValue(inlineRunType);
        if (dataTable == null) return;
        for (Map<String, String> row : dataTable.asMaps(String.class, String.class)) {
            row.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase("RunType"))
                    .map(Map.Entry::getValue)
                    .forEach(DiagnosticRuntime::observeRunTypeValue);
        }
    }

    private static void observeRunTypeValue(String value) {
        if (value == null) return;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("COMPONENT")) observeCapability("scenario.component");
        if (normalized.contains("SERVICE CALL")) observeCapability("service.scenario");
    }

    public static void beginNested(ScenarioStep step) {
        if (DIAGNOSTIC_MODE && reporter != null) reporter.beginNested(ScenarioIdentity.from(step));
    }

    public static void endNested(boolean failed) {
        if (DIAGNOSTIC_MODE && reporter != null) reporter.endNested(failed);
    }

    public static void captureScreenshot(WebDriver driver, String name) {
        if (DIAGNOSTIC_MODE && reporter != null) reporter.captureScreenshot(driver, name);
    }

    public static void captureScreenshotBytes(byte[] imageBytes, String name) {
        if (DIAGNOSTIC_MODE && reporter != null) reporter.captureScreenshotBytes(imageBytes, name);
    }

    public static synchronized void finishRun() {
        if (reporter != null) reporter.finishRun();
    }

    public static String runId() {
        return reporter == null ? "" : reporter.runId();
    }

    public static Path runRoot() {
        return reporter == null ? null : reporter.runRoot();
    }

    public static String reportingMode(Map<String, String> values) {
        String value = find(values, PKB_REPORTING_MODE);
        return value == null ? "normal" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String find(Map<String, String> values, String key) {
        if (values == null) return null;
        return values.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
