// file: tools/dscode/common/reporting/logging/BaseConverter.java
package tools.dscode.common.reporting.logging;

import io.cucumber.core.runner.GlobalState;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public abstract class BaseConverter {

    private String scenarioRowKey;

    public String getScenarioRowKey() {
        if (scenarioRowKey == null)
            this.scenarioRowKey = GlobalState.getCurrentScenarioState().id.toString();
        return scenarioRowKey;
    }

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean scenarioCleanupStarted = new AtomicBoolean(false);
    private final AtomicBoolean runCompleteStarted = new AtomicBoolean(false);
    private final CompletableFuture<Void> scenarioCleanupFuture = new CompletableFuture<>();
    private final CompletableFuture<Void> runCompleteFuture = new CompletableFuture<>();


    /**
     * Optional hook for converters that want to display XLSX row data in their output.
     */
    @FunctionalInterface
    public interface RowDataProvider {
        Optional<RowData> get(String rowKey);
    }

    /**
     * Immutable snapshot of one scenario's row (same headers/values that will be written to XLSX).
     */
    public record RowData(String sheetName, List<String> headers, Map<String, Object> valuesByHeader) {
    }

    private static volatile RowDataProvider rowDataProvider = null;

    /**
     * Framework-level registration; normal callers never need this.
     */
    public static void setRowDataProvider(RowDataProvider provider) {
        rowDataProvider = provider;
    }

    protected final Optional<RowData> rowData(String rowKey) {
        RowDataProvider p = rowDataProvider;
        if (p == null || rowKey == null || rowKey.isBlank()) return Optional.empty();
        return p.get(rowKey);
    }


    public void onStart(Entry scope, Entry entry) {
    }

    public void onTimestamp(Entry scope, Entry entry) {
    }

    public void onStop(Entry scope, Entry entry) {
    }

    // BaseConverter.java

    /**
     * Optional hook: called when a scenario is complete and row data is available. Default no-op.
     */
    protected void onScenarioSummary(Entry scope, String rowKey, RowData data) {
    }

    /**
     * Convenience: fetch row data and call onScenarioSummary(...) if present.
     */
    protected final void renderScenarioSummary(Entry scope, String rowKey) {
        rowData(rowKey).ifPresent(d -> onScenarioSummary(scope, rowKey, d));
    }

    /**
     * Per-scenario close hook.
     * <p>
     * This should finalize only this converter instance's scenario-level work.
     * Do not delete shared attachment temp files or finish an entire external medium here.
     */
    protected void onClose() {
    }

    /**
     * Per-scenario completion hook.
     * <p>
     * Override when a converter has asynchronous scenario work that must drain before
     * scenario-level shared resources can be deleted.  Synchronous converters can
     * inherit the default completed future.
     */
    protected CompletableFuture<Void> onScenarioCleanup() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Run-level completion hook.
     * <p>
     * Override for work that should happen after all scenarios are closed, such as
     * writing a combined report or draining a medium before global cleanup.
     */
    protected CompletableFuture<Void> onRunComplete() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Closes this converter instance (idempotent).
     */
    public final BaseConverter close() {
        if (closed.compareAndSet(false, true)) {
            onClose();
        }
        return this;
    }

    /**
     * Starts this converter's per-scenario cleanup/completion phase once and returns
     * a future that completes when the converter no longer needs scenario resources.
     */
    public final CompletableFuture<Void> cleanupScenario() {
        if (scenarioCleanupStarted.compareAndSet(false, true)) {
            completeFromHook(scenarioCleanupFuture, this::onScenarioCleanup);
        }
        return scenarioCleanupFuture;
    }

    /**
     * Starts this converter's run-level completion phase once and returns a future
     * that completes when run-level converter work has finished.
     */
    public final CompletableFuture<Void> completeRun() {
        if (runCompleteStarted.compareAndSet(false, true)) {
            completeFromHook(runCompleteFuture, this::onRunComplete);
        }
        return runCompleteFuture;
    }

    public final boolean isClosed() {
        return closed.get();
    }

    public final boolean isScenarioCleanupComplete() {
        return scenarioCleanupFuture.isDone() && !scenarioCleanupFuture.isCompletedExceptionally();
    }

    public final boolean isRunComplete() {
        return runCompleteFuture.isDone() && !runCompleteFuture.isCompletedExceptionally();
    }

    public final CompletableFuture<Void> scenarioCleanupFuture() {
        return scenarioCleanupFuture;
    }

    public final CompletableFuture<Void> runCompleteFuture() {
        return runCompleteFuture;
    }

    protected String flatten(Entry entry) {
        return entry == null ? "" : entry.flatten();
    }

    @FunctionalInterface
    private interface CompletionHook {
        CompletableFuture<Void> call();
    }

    private static void completeFromHook(CompletableFuture<Void> target, CompletionHook hook) {
        try {
            CompletableFuture<Void> source = hook.call();
            if (source == null) {
                source = CompletableFuture.completedFuture(null);
            }

            source.whenComplete((ignored, throwable) -> {
                if (throwable == null) {
                    target.complete(null);
                } else {
                    target.completeExceptionally(unwrapCompletionFailure(throwable));
                }
            });
        } catch (Throwable t) {
            target.completeExceptionally(t);
        }
    }

    private static Throwable unwrapCompletionFailure(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }
}
