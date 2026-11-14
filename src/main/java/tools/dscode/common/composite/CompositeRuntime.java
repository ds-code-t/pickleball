package tools.dscode.common.composite;

import dev.domcomposite.Client;
import dev.domcomposite.Session;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import tools.dscode.control.protocol.DirectControlProvider;

/**
 * One composite client for this JVM. Browser startup calls {@link #attach}.
 * A missing engine logs once and leaves the legacy Selenium path in place.
 */
public final class CompositeRuntime {
    public static final String MISSING = "DOM engine not found (no in-repo Java engine on the classpath and no explicit binary); using the legacy Selenium element path for this run";
    public static final String MISSING_FIX =
            "The Java control engine (dev.domengine) ships in the Pickleball jar. Put it on the classpath, or set pkb_composite_bin to a domengine launcher. pkb_composite=off keeps Selenium without this message.";

    private static volatile Decision decision;
    private static volatile Client client;
    private static volatile String joinedCdp;
    /** Join pointer owned by the calling thread. Another thread's {@link #joinedCdp} is not a session. */
    private static final ThreadLocal<String> THREAD_JOINED = new ThreadLocal<>();
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
                    CompositeBinaryLocator.ENGINE_KEY,
                    "pkb_runvars." + CompositeBinaryLocator.ENGINE_KEY,
                    "pkb_dalton",
                    CompositeLog.KEY,
                    "pkb_runvars." + CompositeLog.KEY,
                    CompositeLog.DIR_KEY,
                    "pkb_runvars." + CompositeLog.DIR_KEY,
                    CompositeLog.FALLBACK_KEY,
                    "pkb_runvars." + CompositeLog.FALLBACK_KEY
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
        THREAD_JOINED.remove();
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
        THREAD_JOINED.remove();
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

    /** DevTools endpoint of the session this thread will actually query, not another thread's focus. */
    public static String focusedCdp() {
        Client open = client;
        if (open != null) {
            Session session = open.currentSession();
            if (session != null && !session.isClosed()) {
                String cdp = session.cdp();
                if (cdp != null && !cdp.isBlank()) return cdp;
            }
        }
        return THREAD_JOINED.get();
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
     *
     * @return false when a page session was requested and did not open. The
     * destination tab is not this thread's session in that case.
     */
    public static boolean focusPage(WebDriver driver, String pageTarget) {
        if (!(driver instanceof RemoteWebDriver remote)) return true;
        String raw = cdpAddress(remote.getCapabilities());
        if (raw == null) return true;
        String browser = DirectControlProvider.withScheme(raw);
        String normalized = pageTarget == null || pageTarget.isBlank()
                ? browser
                : Client.withTarget(browser, pageTarget);
        String previousGlobal = joinedCdp;
        String previousThread = THREAD_JOINED.get();
        String key = Client.cdpKey(normalized);
        String id = SESSIONS_BY_CDP.get(key);
        Client open = client;
        if (open != null && id != null) {
            open.focusSession(id);
            rememberJoined(normalized);
            return true;
        }
        if (pageTarget != null && !pageTarget.isBlank()) {
            if (open == null) return true;
            if (!openSession(normalized, key)) {
                joinedCdp = previousGlobal;
                if (previousThread == null || previousThread.isBlank()) THREAD_JOINED.remove();
                else THREAD_JOINED.set(previousThread);
                return false;
            }
            return true;
        }
        String browserId = SESSIONS_BY_CDP.get(Client.cdpKey(browser));
        if (open != null && browserId != null) {
            open.focusSession(browserId);
            rememberJoined(browser);
        }
        return true;
    }

    /** True after {@link #attach} joined a CDP browser. A process-only session is not joined. */
    public static boolean browserJoined() {
        String joined = joinedCdp;
        return joined != null && !joined.isBlank();
    }

    /** Test seam. The global join pointer, which must not name a session that failed to open. */
    public static String joinedForTests() {
        return joinedCdp;
    }

    /** Test seam. Production join is {@link #attach}. */
    public static void joinForTests(String cdp) {
        rememberJoined(cdp);
    }

    /** Opens the thread session on demand. Returns null when this run is on Selenium. */
    public static Client ensureClient() {
        Client open = client();
        if (open == null) return null;
        if (open.currentSession() != null) return open;
        String own = THREAD_JOINED.get();
        // A blank join on this thread must not copy another thread's endpoint.
        // With no join anywhere, a process-only session is still this thread's.
        if ((own == null || own.isBlank()) && joinedCdp != null && !joinedCdp.isBlank()) return open;
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("process", true);
        if (own != null && !own.isBlank()) {
            config.put("cdp", own);
            config.put("i_accept_local_attach", true);
        }
        open.openSession(config);
        CompositeLog.info("session", "opened " + open.currentSession().id());
        return open;
    }

    /** Opens a client when the binary is present. Does not throw. A second browser keeps its own session. */
    public static void attach(RemoteWebDriver driver) {
        try {
            Decision now = current();
            if (!now.primary) return;
            String raw = driver == null ? null : cdpAddress(driver.getCapabilities());
            if (raw == null) return;
            String normalized = DirectControlProvider.withScheme(raw);
            String key = Client.cdpKey(normalized);
            String known = SESSIONS_BY_CDP.get(key);
            if (known != null) {
                rememberJoined(normalized);
                if (client != null) client.focusSession(known);
                return;
            }
            if (client != null && client.currentSession() != null && normalized.equals(joinedCdp)) {
                SESSIONS_BY_CDP.put(key, client.currentSession().id());
                rememberJoined(normalized);
                return;
            }
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("cdp", normalized);
            config.put("i_accept_local_attach", true);
            config.put("process", true);
            String page = pageTargetId(driver);
            if (page != null) config.put("target", page);
            Session session = client.openSession(config);
            SESSIONS_BY_CDP.put(key, session.id());
            rememberJoined(normalized);
            CompositeLog.info("session", "joined " + normalized + " id=" + session.id());
        } catch (RuntimeException failure) {
            noteJoinFailure(
                    "DOM engine could not join the browser session: " + failure.getMessage()
            );
            if (SESSIONS_BY_CDP.isEmpty()) forceLegacy();
        }
    }

    /** @return false when the page session did not open. Does not leave {@code joinedCdp} on that target. */
    private static boolean openSession(String normalized, String key) {
        Client open = client;
        if (open == null || normalized == null || normalized.isBlank()) return false;
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("cdp", normalized);
            config.put("i_accept_local_attach", true);
            config.put("process", true);
            Session session = open.openSession(config);
            SESSIONS_BY_CDP.put(key, session.id());
            rememberJoined(normalized);
            CompositeLog.info("session", "joined " + normalized + " id=" + session.id());
            return true;
        } catch (RuntimeException failure) {
            noteJoinFailure("DOM engine could not join the page session: " + failure.getMessage());
            return false;
        }
    }

    /**
     * Optional Dalton note. The engine stays usable when Dalton is off.
     * This adapter writes one line; it does not open the Dalton database.
     */
    private static void recordDalton(Sources sources, Path binary, String version) {
        String flag = CompositeMode.read("pkb_dalton", sources);
        if (flag == null || !(flag.equalsIgnoreCase("true") || "1".equals(flag))) return;
        Path root = sources.project() == null ? Path.of("") : sources.project();
        Path file = root.resolve(".pickleball").resolve("dalton").resolve("engine-sessions.log");
        String line = Instant.now() + " engine=" + version + " bin=" + binary + "\n";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException failure) {
            CompositeLog.debug("session", "dalton note: " + failure.getMessage());
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
        if (!mode.required()) {
            if (mode.disabled()) {
                CompositeLog.debug("location", "pkb_composite=off");
                return Decision.legacy("off");
            }
            CompositeLog.info("location", "library selenium");
            return Decision.legacy("library");
        }
        CompositeLog.info("location", "pkb_composite=on");
        CompositeLog.compatibilityOn();
        Optional<Path> binary = CompositeBinaryLocator.locate(sources);
        if (binary.isEmpty()) {
            CompositeLog.fallbackOnce("missing", MISSING, MISSING_FIX, null);
            return Decision.legacy("missing");
        }
        try {
            Client opened = new Client(binary.get().toString(), logMap());
            String version = opened.version().path("version").asText("");
            client = opened;
            CompositeLog.info("session", "client " + binary.get() + " version " + version);
            recordDalton(sources, binary.get(), version);
            return new Decision(true, binary.get(), version, "composite");
        } catch (RuntimeException failure) {
            CompositeLog.fallbackOnce(
                    "client",
                    "DOM engine client failed (" + failure.getMessage() + "); using the legacy Selenium element path for this run",
                    MISSING_FIX,
                    binary.get().toString()
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

    private static void rememberJoined(String cdp) {
        joinedCdp = cdp == null || cdp.isBlank() ? null : cdp;
        if (joinedCdp == null) THREAD_JOINED.remove();
        else THREAD_JOINED.set(joinedCdp);
    }

    private static void noteJoinFailure(String error) {
        Decision prior = decision;
        String engine = prior == null || prior.binary() == null ? null : prior.binary().toString();
        CompositeLog.fallbackOnce(
                "join",
                error,
                "pkb_composite=on asked for the Java engine and the join failed. Check that the engine is on the classpath, or fix the explicit pkb_composite_bin / COMPOSITE_BIN path. Unset, auto, and off stay on SeleniumAdapter.",
                engine
        );
    }

    private static void forceLegacy() {
        decision = Decision.legacy("join");
    }

    /**
     * Current tab from {@code Target.getTargetInfo}, or null when this driver
     * has no CDP command. The joined address stays the browser endpoint; the
     * engine uses this id to open the page socket.
     */
    static String pageTargetId(WebDriver driver) {
        if (driver == null) return null;
        try {
            java.lang.reflect.Method method = driver.getClass().getMethod(
                    "executeCdpCommand", String.class, Map.class);
            Object raw = method.invoke(driver, "Target.getTargetInfo", Map.of());
            if (!(raw instanceof Map<?, ?> map)) return null;
            Object body = map.get("targetInfo") instanceof Map<?, ?> nested ? nested : map;
            if (!(body instanceof Map<?, ?> target)) return null;
            Object id = target.get("targetId");
            if (id == null || id.toString().isBlank()) return null;
            return id.toString();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Debugger address already published on the driver. {@code se:cdp} wins.
     * Otherwise the Chromium {@code debuggerAddress} on {@code goog:chromeOptions}
     * or {@code ms:edgeOptions}: the map that matches {@code browserName}
     * (edge vs chrome) is read first, then the other. Null when none of those
     * fields is present. {@link #attach} sends that address plus the current
     * page target when the driver can answer {@code Target.getTargetInfo}.
     * The engine joins that page. A browser-level socket is not a DOM target.
     * A null page target passed to {@link #focusPage} keeps the browser-keyed session.
     */
    public static String cdpAddress(Capabilities capabilities) {
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
