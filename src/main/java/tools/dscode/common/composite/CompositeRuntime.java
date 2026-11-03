package tools.dscode.common.composite;

import dev.domcomposite.Client;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.dscode.control.protocol.DirectControlProvider;

/**
 * One composite client for this JVM. Browser startup calls {@link #attach}.
 * A missing binary logs once and leaves the legacy Selenium path in place.
 */
public final class CompositeRuntime {
    public static final String MISSING = "dom-composite binary not found; using the legacy Selenium element path for this run";
    public static final String MISSING_FIX =
            "Set COMPOSITE_BIN, COMPOSITE_HOME, or pkb_composite_bin, or build the sibling dom-composite checkout (target/release/composite). pkb_composite=off keeps Selenium without this message.";

    private static volatile Decision decision;
    private static volatile Client client;
    private static volatile String joinedCdp;

    private CompositeRuntime() {
    }

    public static void resetForTests() {
        Client open = client;
        client = null;
        decision = null;
        joinedCdp = null;
        CompositeLog.resetForTests();
        if (open != null) {
            try {
                open.close();
            } catch (RuntimeException ignored) {
                // The test stub may already have exited.
            }
        }
    }

    public static boolean primary() {
        return current().primary;
    }

    public static Decision current() {
        Decision cached = decision;
        if (cached != null) return cached;
        synchronized (CompositeRuntime.class) {
            if (decision != null) return decision;
            decision = decide();
            return decision;
        }
    }

    public static Client client() {
        Decision now = current();
        if (!now.primary) return null;
        return client;
    }

    /** Opens the thread session on demand. Returns null when this run is on Selenium. */
    public static Client ensureClient() {
        Client open = client();
        if (open == null) return null;
        if (open.currentSession() != null) return open;
        Map<String, Object> config = new LinkedHashMap<>();
        if (joinedCdp != null) {
            config.put("cdp", joinedCdp);
            config.put("i_accept_local_attach", true);
        }
        config.put("process", true);
        open.openSession(config);
        CompositeLog.info("session", "opened " + open.currentSession().id());
        return open;
    }

    /** Opens a client when the binary is present. Does not throw. */
    public static void attach(RemoteWebDriver driver) {
        try {
            Decision now = current();
            if (!now.primary) return;
            String cdp = driver == null ? null : cdp(driver.getCapabilities());
            if (cdp == null || cdp.equals(joinedCdp)) return;
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("cdp", DirectControlProvider.withScheme(cdp));
            config.put("i_accept_local_attach", true);
            config.put("process", true);
            client.openSession(config);
            joinedCdp = cdp;
            CompositeLog.info("session", "joined " + cdp);
        } catch (RuntimeException failure) {
            CompositeLog.errorFix(
                    "session",
                    "dom-composite could not join the browser session: " + failure.getMessage(),
                    "Leave pkb_composite=auto so this run stays on Selenium, or fix COMPOSITE_BIN."
            );
            forceLegacy();
        }
    }

    /** Test and startup probe. Spawns {@code composite serve --stdio} and asks for {@code version}. */
    public static String openProbe(Path binary) {
        Client opened = new Client(binary == null ? null : binary.toString(), logMap());
        try {
            return opened.version().path("version").asText("");
        } finally {
            opened.close();
        }
    }

    private static Decision decide() {
        CompositeMode mode = CompositeMode.current();
        if (mode.disabled()) {
            CompositeLog.debug("location", "pkb_composite=off");
            return Decision.legacy("off");
        }
        Optional<Path> binary = CompositeBinaryLocator.locate();
        if (binary.isEmpty()) {
            CompositeLog.fallbackOnce(MISSING, MISSING_FIX);
            return Decision.legacy("missing");
        }
        try {
            Client opened = new Client(binary.get().toString(), logMap());
            String version = opened.version().path("version").asText("");
            client = opened;
            CompositeLog.info("session", "client " + binary.get() + " version " + version);
            return new Decision(true, binary.get(), version, "composite");
        } catch (RuntimeException failure) {
            CompositeLog.fallbackOnce(
                    "dom-composite client failed (" + failure.getMessage() + "); using the legacy Selenium element path for this run",
                    MISSING_FIX
            );
            return Decision.legacy("client");
        }
    }

    private static void forceLegacy() {
        decision = Decision.legacy("join");
    }

    /** Same fields as {@code DebuggerAddress}. Core cannot depend on the control-api module. */
    private static String cdp(Capabilities capabilities) {
        if (capabilities == null) return null;
        String devtools = text(capabilities.getCapability("se:cdp"));
        if (devtools != null) return devtools;
        String browser = text(capabilities.getCapability("browserName"));
        boolean edge = browser != null && browser.toLowerCase(Locale.ROOT).contains("edge");
        String preferred = edge ? "ms:edgeOptions" : "goog:chromeOptions";
        String address = debugger(capabilities.getCapability(preferred));
        if (address != null) return address;
        return debugger(capabilities.getCapability(edge ? "goog:chromeOptions" : "ms:edgeOptions"));
    }

    private static String debugger(Object options) {
        if (!(options instanceof Map<?, ?> map)) return null;
        return text(map.get("debuggerAddress"));
    }

    private static String text(Object value) {
        if (value == null) return null;
        String trimmed = value.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, Object> logMap() {
        if (!CompositeLog.enabled("session") && !CompositeLog.enabled("adapter") && !CompositeLog.enabled("element")) {
            return Map.of();
        }
        Map<String, Object> log = new LinkedHashMap<>();
        String entities = CompositeMode.setting(CompositeLog.KEY);
        log.put("entities", entities == null ? "session,action" : entities);
        log.put("level", "info");
        log.put("dir", CompositeLog.file().getParent().toString());
        log.put("capBytes", CompositeLog.CAP_BYTES);
        return log;
    }

    public record Decision(boolean primary, Path binary, String version, String reason) {
        private static Decision legacy(String reason) {
            return new Decision(false, null, "", reason);
        }
    }
}
