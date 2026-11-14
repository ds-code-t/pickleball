package tools.dscode.common.seleniumextensions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;
import dev.domengine.snapshot.SnapshotOptions;
import dev.domcomposite.Client;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.browser.BrowserFacade;
import tools.dscode.browser.ProtocolSelector;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.composite.CompositeWebElement;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;

/**
 * The only construction entry for an {@link ElementSnapshot}.
 * Composite hits use the shared script through the driver. Every other
 * element uses {@link SeleniumSnapshotProducer}.
 */
public final class SnapshotProducers {
    private static volatile Function<WebDriver, BrowserFacade> facadesForTests;

    private SnapshotProducers() {
    }

    /** Test seam. {@code null} restores {@link ProtocolSelector#forDriver(WebDriver)}. */
    public static void useFacadeForTests(Function<WebDriver, BrowserFacade> facades) {
        facadesForTests = facades;
    }

    /**
     * Page composite snapshot. The joined engine wins when it lists {@code snapshot_schema}.
     * Otherwise the facade (BiDi or CDP) builds one. A missing adapter or an adapter error
     * logs one {@code WARN snapshot fallback selenium reason=} line and returns a
     * {@code selenium-fallback} artifact.
     */
    public static JsonNode page(WebDriver driver, SnapshotOptions options) {
        SnapshotOptions used = options == null ? new SnapshotOptions() : options;
        JsonNode engine = tryEngine(used);
        if (engine != null) return engine;
        BrowserFacade facade;
        try {
            facade = facadeFor(driver);
        } catch (RuntimeException failure) {
            CompositeLog.snapshotFallback("adapter-error:" + oneLine(failure));
            return FacadeSnapshotProducer.selenium(driver, used);
        }
        if (facade == null || "selenium".equals(facade.protocol())) {
            CompositeLog.snapshotFallback("no-adapter");
            return FacadeSnapshotProducer.selenium(driver, used);
        }
        try {
            return FacadeSnapshotProducer.capture(
                    facade, used, FacadeSnapshotProducer.platformOf(driver, facade.protocol()), transport(facade));
        } catch (RuntimeException failure) {
            CompositeLog.snapshotFallback("adapter-error:" + oneLine(failure));
            return FacadeSnapshotProducer.selenium(driver, used);
        }
    }

    public static ElementSnapshot capture(WebDriver driver, WebElement element, String event) {
        long start = System.nanoTime();
        boolean composite = element instanceof CompositeWebElement;
        ElementSnapshot snapshot = composite
                ? CompositeSnapshotProducer.INSTANCE.snapshot(driver, element)
                : SeleniumSnapshotProducer.INSTANCE.snapshot(driver, element);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        logTrace("element-snapshot event=" + (event == null ? "create" : event)
                + " mode=" + (composite ? "composite" : "selenium")
                + " selector=" + snapshot.stableSelector()
                + " nodeId=" + (snapshot.nodeId() == null ? "" : snapshot.nodeId())
                + " durationMs=" + durationMs);
        return snapshot;
    }

    private static BrowserFacade facadeFor(WebDriver driver) {
        Function<WebDriver, BrowserFacade> custom = facadesForTests;
        if (custom != null) return custom.apply(driver);
        return ProtocolSelector.forDriver(driver);
    }

    private static String transport(BrowserFacade facade) {
        String protocol = facade.protocol();
        if ("bidi".equals(protocol)) return "bidi";
        if ("cdp".equals(protocol)) return "cdp";
        return "selenium-fallback";
    }

    private static JsonNode tryEngine(SnapshotOptions options) {
        if (!CompositeRuntime.browserJoined()) return null;
        try {
            Client open = CompositeRuntime.ensureClient();
            if (open == null || !schemaListed(open)) return null;
            ObjectNode body = Jsons.object();
            options.flags().forEach(body::put);
            return open.snapshot(body);
        } catch (RuntimeException failure) {
            CompositeLog.info("snapshot", "engine " + failure.getMessage());
            return null;
        }
    }

    private static boolean schemaListed(Client open) {
        JsonNode caps = open.capabilities();
        JsonNode schema = caps == null ? null : caps.get("snapshot_schema");
        if (schema == null || schema.isNull()) return false;
        if (schema.isNumber()) return schema.asInt() >= 1;
        return !schema.asText("").isBlank();
    }

    private static String oneLine(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        int newline = message.indexOf('\n');
        if (newline >= 0) message = message.substring(0, newline);
        return message;
    }
}
