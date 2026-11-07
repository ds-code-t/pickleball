package tools.dscode.common.composite;

import dev.domcomposite.Client;
import dev.domcomposite.Session;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
    private static volatile Sources override;
    private static final Map<String, String> SESSIONS_BY_CDP = new ConcurrentHashMap<>();

    private CompositeRuntime() {
    }

    /**
     * The inputs {@link #decide(Sources)} reads. Production uses {@link #live()}.
     * Tests call {@link #use(Path, Map, ClassLoader, Path)}, which installs an
     * override with an empty property map so host {@code -D}, host env, cwd
     * siblings, {@code ~/.m2}, and {@code PATH} cannot leak in.
     */
    public record Sources(
            Path project,
            Map<String, String> env,
            ClassLoader loader,
            Path home,
            Map<String, String> properties,
            boolean runVars
    ) {
        public Sources {
            env = env == null ? Map.of() : Map.copyOf(env);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }

        public static Sources live() {
            Map<String, String> properties = new LinkedHashMap<>();
            for (String key : List.of(
                    CompositeBinaryLocator.BIN_KEY,
                    "pkb_runvars." + CompositeBinaryLocator.BIN_KEY,
                    CompositeMode.KEY,
                    "pkb_runvars." + CompositeMode.KEY,
                    CompositeLog.KEY,
                    "pkb_runvars." + CompositeLog.KEY,
                    CompositeLog.DIR_KEY,
                    "pkb_runvars." + CompositeLog.DIR_KEY
            )) {
                String value = System.getProperty(key);
                if (value != null) properties.put(key, value);
            }
            String home = System.getProperty("user.home", "");
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            return new Sources(
                    Path.of("").toAbsolutePath(),
                    System.getenv(),
                    loader == null ? ClassLoader.getSystemClassLoader() : loader,
                    home.isBlank() ? null : Path.of(home),
                    properties,
                    true
            );
        }
    }

    /** Active test override, or null when this JVM should use {@link Sources#live()}. */
    static Sources pinned() {
        return override;
    }

    static Sources sources() {
        Sources pinned = override;
        return pinned != null ? pinned : Sources.live();
    }

    /**
     * Pins the runtime decision to these inputs. Properties are empty, so a
     * host {@code -Dpkb_composite*} or {@code PKB_COMPOSITE} is not visible
     * until {@link #resetForTests()}.
     */
    public static void use(Path project, Map<String, String> env, ClassLoader loader, Path home) {
        closeClient();
        decision = null;
        joinedCdp = null;
        SESSIONS_BY_CDP.clear();
        override = new Sources(
                project == null ? Path.of("").toAbsolutePath() : project,
                env,
                loader,
                home,
                Map.of(),
                false
        );
        CompositeLog.resetForTests();
    }

    public static void resetForTests() {
        closeClient();
        decision = null;
        joinedCdp = null;
        SESSIONS_BY_CDP.clear();
        override = null;
        CompositeLog.resetForTests();
        DomCompositeAdapter.get().resetDryRunForTests();
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

    /** DevTools endpoint of the browser this thread's engine session is focused on. */
    public static String focusedCdp() {
        return joinedCdp;
    }

    /** Session id query and observe use when the caller does not pass one. */
    public static String focusedSessionId() {
        Client open = client;
        if (open == null) return null;
        Session session = open.currentSession();
        return session == null ? null : session.id();
    }

    /** Window and tab focus follow the driver's engine session, not the first join. */
    public static void focusDriver(WebDriver driver) {
        focusPage(driver, null);
    }

    /**
     * Select the engine Session for this browser, or for one page target when
     * {@code pageTarget} is a CDP target id or a devtools page URL. Two tabs on
     * one host:port keep two sessions. A null target keeps the browser session.
     */
    public static void focusPage(WebDriver driver, String pageTarget) {
        if (!(driver instanceof RemoteWebDriver remote)) return;
        String raw = cdp(remote.getCapabilities());
        if (raw == null) return;
        String browser = DirectControlProvider.withScheme(raw);
        String normalized = pageTarget == null || pageTarget.isBlank()
                ? browser
                : Client.withTarget(browser, pageTarget);
        joinedCdp = normalized;
        String key = Client.cdpKey(normalized);
        String id = SESSIONS_BY_CDP.get(key);
        Client open = client;
        if (open != null && id != null) {
            open.focusSession(id);
            return;
        }
        if (pageTarget != null && !pageTarget.isBlank()) {
            openSession(normalized, key);
            return;
        }
        String browserId = SESSIONS_BY_CDP.get(Client.cdpKey(browser));
        if (open != null && browserId != null) open.focusSession(browserId);
    }

    /** True after {@link #attach} joined a CDP browser. A process-only session is not joined. */
    public static boolean browserJoined() {
        String joined = joinedCdp;
        return joined != null && !joined.isBlank();
    }

    /** Test seam. Production join is {@link #attach}. */
    public static void joinForTests(String cdp) {
        joinedCdp = cdp == null || cdp.isBlank() ? null : cdp;
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

    /** Opens a client when the binary is present. Does not throw. A second browser keeps its own session. */
    public static void attach(RemoteWebDriver driver) {
        try {
            Decision now = current();
            if (!now.primary) return;
            String raw = driver == null ? null : cdp(driver.getCapabilities());
            if (raw == null) return;
            String normalized = DirectControlProvider.withScheme(raw);
            String key = Client.cdpKey(normalized);
            String known = SESSIONS_BY_CDP.get(key);
            if (known != null) {
                joinedCdp = normalized;
                if (client != null) client.focusSession(known);
                return;
            }
            if (client != null && client.currentSession() != null && normalized.equals(joinedCdp)) {
                SESSIONS_BY_CDP.put(key, client.currentSession().id());
                return;
            }
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("cdp", normalized);
            config.put("i_accept_local_attach", true);
            config.put("process", true);
            Session session = client.openSession(config);
            SESSIONS_BY_CDP.put(key, session.id());
            joinedCdp = normalized;
            CompositeLog.info("session", "joined " + normalized + " id=" + session.id());
        } catch (RuntimeException failure) {
            CompositeLog.errorFix(
                    "session",
                    "dom-composite could not join the browser session: " + failure.getMessage(),
                    "Leave pkb_composite=auto so this run stays on Selenium, or fix COMPOSITE_BIN."
            );
            if (SESSIONS_BY_CDP.isEmpty()) forceLegacy();
        }
    }

    private static void openSession(String normalized, String key) {
        Client open = client;
        if (open == null || normalized == null || normalized.isBlank()) return;
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("cdp", normalized);
            config.put("i_accept_local_attach", true);
            config.put("process", true);
            Session session = open.openSession(config);
            SESSIONS_BY_CDP.put(key, session.id());
            joinedCdp = normalized;
            CompositeLog.info("session", "joined " + normalized + " id=" + session.id());
        } catch (RuntimeException failure) {
            CompositeLog.errorFix(
                    "session",
                    "dom-composite could not join the page session: " + failure.getMessage(),
                    "Leave pkb_composite=auto so this run stays on Selenium, or fix COMPOSITE_BIN."
            );
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
        return decide(sources());
    }

    static Decision decide(Sources sources) {
        CompositeMode mode = CompositeMode.parse(CompositeMode.read(CompositeMode.KEY, sources));
        if (mode.disabled()) {
            CompositeLog.debug("location", "pkb_composite=off");
            return Decision.legacy("off");
        }
        Optional<Path> binary = CompositeBinaryLocator.locate(sources);
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

    private static void closeClient() {
        Client open = client;
        client = null;
        if (open != null) {
            try {
                open.close();
            } catch (RuntimeException ignored) {
                // The test stub may already have exited.
            }
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
