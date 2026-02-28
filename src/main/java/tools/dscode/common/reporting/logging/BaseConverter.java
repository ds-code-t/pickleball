// file: tools/dscode/common/reporting/logging/BaseConverter.java
package tools.dscode.common.reporting.logging;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

import java.util.concurrent.atomic.AtomicBoolean;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public abstract class BaseConverter {

    private final AtomicBoolean closed = new AtomicBoolean(false);


    /** Optional hook for converters that want to display XLSX row data in their output. */
    @FunctionalInterface
    public interface RowDataProvider {
        Optional<RowData> get(String rowKey);
    }

    /** Immutable snapshot of one scenario's row (same headers/values that will be written to XLSX). */
    public record RowData(String sheetName, List<String> headers, Map<String, Object> valuesByHeader) { }

    private static volatile RowDataProvider rowDataProvider = null;

    /** Framework-level registration; normal callers never need this. */
    public static void setRowDataProvider(RowDataProvider provider) {
        rowDataProvider = provider;
    }

    protected final Optional<RowData> rowData(String rowKey) {
        RowDataProvider p = rowDataProvider;
        if (p == null || rowKey == null || rowKey.isBlank()) return Optional.empty();
        return p.get(rowKey);
    }


    public void onStart(Entry scope, Entry entry) { }
    public void onTimestamp(Entry scope, Entry entry) { }
    public void onStop(Entry scope, Entry entry) { }

    // BaseConverter.java

    /** Optional hook: called when a scenario is complete and row data is available. Default no-op. */
    protected void onScenarioSummary(Entry scope, String rowKey, RowData data) { }

    /** Convenience: fetch row data and call onScenarioSummary(...) if present. */
    protected final void renderScenarioSummary(Entry scope, String rowKey) {
        rowData(rowKey).ifPresent(d -> onScenarioSummary(scope, rowKey, d));
    }

    /** Override for flush / write / cleanup logic */
    protected void onClose() { }

    /** Closes this converter instance (idempotent). */
    public final BaseConverter close() {
        if (closed.compareAndSet(false, true)) {
            onClose();
        }
        return this;
    }

    public final boolean isClosed() {
        return closed.get();
    }

    public Entry screenshot(Entry entry, WebDriver driver) {
        return screenshot(entry, driver, null);
    }

    public Entry screenshot(Entry entry, WebDriver driver, String name) {
        String base64 = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BASE64);
        return entry.attach(name, "image/png;base64", base64);
    }

}
