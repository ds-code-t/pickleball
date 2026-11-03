package tools.dscode.common.seleniumextensions;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.composite.CompositeWebElement;

import java.util.concurrent.TimeUnit;

import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;

/**
 * The only construction entry for an {@link ElementSnapshot}.
 * Composite hits use the shared script through the driver. Every other
 * element uses {@link SeleniumSnapshotProducer}.
 */
public final class SnapshotProducers {
    private SnapshotProducers() {
    }

    public static ElementSnapshot capture(WebDriver driver, WebElement element, String event) {
        long start = System.nanoTime();
        boolean composite = element instanceof CompositeWebElement;
        ElementSnapshot snapshot = composite
                ? ElementSnapshotScript.onComposite(driver, (CompositeWebElement) element)
                : SeleniumSnapshotProducer.INSTANCE.snapshot(driver, element);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        logTrace("element-snapshot event=" + (event == null ? "create" : event)
                + " mode=" + (composite ? "composite" : "selenium")
                + " selector=" + snapshot.stableSelector()
                + " nodeId=" + (snapshot.nodeId() == null ? "" : snapshot.nodeId())
                + " durationMs=" + durationMs);
        return snapshot;
    }
}
