package tools.dscode.browser;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.bidi.log.BaseLogEntry;
import org.openqa.selenium.bidi.module.LogInspector;
import org.openqa.selenium.bidi.module.Network;
import org.openqa.selenium.bidi.network.BaseParameters;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.browser.proto.BrowserEventHub;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * E11: browser-event rows for a scenario's own WebDriver session, read through Selenium's high-level BiDi modules
 * ({@link LogInspector} for console and page errors, {@link Network} for requests). Selenium already holds the BiDi
 * socket, so no second connection is opened. Opt-in with {@code pkb_scenario_events=on}; rows use the E2 schema
 * ({@code source=bidi}, {@code transport=selenium}) and spill like any other session.
 */
public final class SeleniumBidiEvents {
    private static final Map<WebDriver, Watch> WATCHES = new WeakHashMap<>();
    private static volatile boolean warnedNoBidi;

    private SeleniumBidiEvents() {
    }

    /** The modules and hub for one driver. */
    public static final class Watch implements AutoCloseable {
        final BrowserEventHub hub;
        LogInspector log;
        Network network;

        Watch(BrowserEventHub hub) {
            this.hub = hub;
        }

        public BrowserEventHub hub() {
            return hub;
        }

        @Override
        public void close() {
            if (log != null) log.close();
            if (network != null) network.close();
        }
    }

    /** The five BiDi subscriptions {@link #watch(WebDriver, tools.dscode.browser.proto.BrowserEventHub)} registers. */
    static List<String> eventNames() {
        return List.of(
                "onConsoleEntry",
                "onJavaScriptException",
                "onBeforeRequestSent",
                "onResponseCompleted",
                "onFetchError");
    }

    public static boolean enabled() {
        String value = BrowserProtocols.setting("pkb_scenario_events");
        return value != null && java.util.Set.of("on", "true", "yes", "1").contains(value.trim().toLowerCase(Locale.ROOT));
    }

    /** Starts rows for this driver when {@code pkb_scenario_events=on}; once per driver. */
    public static void watch(WebDriver driver) {
        if (driver == null || !enabled()) return;
        synchronized (WATCHES) {
            if (WATCHES.containsKey(driver)) return;
        }
        String id = driver instanceof RemoteWebDriver r && r.getSessionId() != null ? r.getSessionId().toString() : Integer.toHexString(driver.hashCode());
        watch(driver, new BrowserEventHub(Path.of("").toAbsolutePath(), "scenario-" + id.substring(0, Math.min(8, id.length())), runId()));
    }

    /** Feeds this hub from the driver's BiDi connection. Returns null when the session has no BiDi. */
    public static Watch watch(WebDriver driver, BrowserEventHub hub) {
        Watch watch = new Watch(hub);
        try {
            watch.log = new LogInspector(driver);
            watch.log.onConsoleEntry(entry -> hub.add(log(hub, "console", "log", entry).put("method", entry.getMethod())));
            watch.log.onJavaScriptException(entry -> hub.add(log(hub, "error", "uncaught", entry)));
            watch.network = new Network(driver);
            watch.network.onBeforeRequestSent(e -> hub.add(net(hub, "request", e).put("method", e.getRequest().getMethod())));
            watch.network.onResponseCompleted(e -> hub.add(net(hub, "response", e).put("status", e.getResponseData().getStatus())
                    .put("bytes", e.getResponseData().getBytesReceived())));
            watch.network.onFetchError(e -> hub.add(net(hub, "failed", e).put("text", e.getErrorText()).put("level", "error")));
        } catch (RuntimeException noBidi) {
            watch.close();
            if (!warnedNoBidi) {
                warnedNoBidi = true;
                System.err.println("WARN pkb_scenario_events needs WebDriver BiDi (webSocketUrl); scenario browser events are not collected");
            }
            return null;
        }
        synchronized (WATCHES) {
            WATCHES.put(driver, watch);
        }
        return watch;
    }

    /** The watch for a driver, if any (tests and reports). */
    public static Watch of(WebDriver driver) {
        synchronized (WATCHES) {
            return WATCHES.get(driver);
        }
    }

    public static void stop(WebDriver driver) {
        Watch watch;
        synchronized (WATCHES) {
            watch = WATCHES.remove(driver);
        }
        if (watch != null) watch.close();
    }

    static ObjectNode log(BrowserEventHub hub, String kind, String subkind, BaseLogEntry entry) {
        ObjectNode row = hub.row("bidi", kind, subkind).put("transport", "selenium").put("text", entry.getText());
        String level = entry.getLevel() == null ? "info" : entry.getLevel().toString().toLowerCase(Locale.ROOT);
        row.put("level", "warn".equals(level) ? "warning" : level);
        if (entry.getSource() != null) entry.getSource().getBrowsingContext().ifPresent(c -> row.put("context", c));
        return row;
    }

    static ObjectNode net(BrowserEventHub hub, String subkind, BaseParameters e) {
        ObjectNode row = hub.row("bidi", "net", subkind).put("transport", "selenium").put("requestId", e.getRequest().getRequestId())
                .put("url", e.getRequest().getUrl());
        if (e.getBrowsingContextId() != null) row.put("context", e.getBrowsingContextId());
        return row;
    }

    static String runId() {
        String run = System.getProperty("PKB_RUN_ID", System.getenv("PKB_RUN_ID"));
        return run == null || run.isBlank() ? "live" : run;
    }
}
