package tools.dscode.common.driver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumDriverLogLevel;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeDriverService;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.service.DriverService;

import java.io.File;
import java.io.OutputStream;
import java.net.URL;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.common.domoperations.SeleniumUtils.ensureDevToolsPort;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

public class DriverWrapper implements AutoCloseable {

    private static final Set<String> RESERVED_ROOT_KEYS = Set.of(
            "connection",
            "capabilities",
            "service",
            "postStart",
            "metadata",
            "framework",
            "constructor",
            "_pathKey",
            "browser",
            "browserName"
    );

    private final RemoteWebDriver driver;
    private final ObjectNode configuration;

    public DriverWrapper(RemoteWebDriver driver, ObjectNode configuration) {
        this.driver = driver;
        this.configuration = configuration == null ? null : configuration.deepCopy();
    }

    public RemoteWebDriver getDriver() {
        return driver;
    }

    public ObjectNode getConfiguration() {
        return configuration == null ? null : configuration.deepCopy();
    }

    public String getPathKey() {
        return configuration == null ? null : trimToNull(configuration.path("_pathKey").asText(null));
    }

    @Override
    public void close() {
        if (driver != null) {
            driver.quit();
        }
    }

    public static DriverWrapper createDriver(ObjectNode configuration) throws Exception {
        ObjectNode config = requireConfiguration(configuration);
        BrowserConfig browserConfig = BrowserConfig.from(toRawMap(config));
        String browserName = resolveBrowserName(config, browserConfig);

        RemoteWebDriver driver = hasRemoteUrl(browserConfig)
                ? createRemoteDriver(browserName, browserConfig, config, "Driver remoteUrl not found")
                : createLocalDriver(browserName, browserConfig, config);

        return new DriverWrapper(driver, config);
    }

    public static DriverWrapper createLocalDriver(ObjectNode configuration) throws Exception {
        ObjectNode config = requireConfiguration(configuration);
        BrowserConfig browserConfig = BrowserConfig.from(toRawMap(config));
        String browserName = resolveBrowserName(config, browserConfig);
        RemoteWebDriver driver = createLocalDriver(browserName, browserConfig, config);
        return new DriverWrapper(driver, config);
    }

    public static DriverWrapper createRemoteDriver(ObjectNode configuration) throws Exception {
        ObjectNode config = requireConfiguration(configuration);
        BrowserConfig browserConfig = BrowserConfig.from(toRawMap(config));
        String browserName = resolveBrowserName(config, browserConfig);
        RemoteWebDriver driver = createRemoteDriver(browserName, browserConfig, config, "Driver remoteUrl not found");
        return new DriverWrapper(driver, config);
    }

    public static RemoteWebDriver createLocalDriver(String browserName, BrowserConfig config, ObjectNode fullConfiguration) throws Exception {
        ClientConfig clientConfig = buildClientConfig(config.connection());

        return switch (browserName) {
            case "chrome" -> {
                ChromeOptions options = buildChromeOptions(config.capabilities(), fullConfiguration);
                ChromeDriverService service = buildChromeService(config.service());

                ChromeDriver driver =
                        service != null && clientConfig != null ? new ChromeDriver(service, options, clientConfig)
                                : service != null ? new ChromeDriver(service, options)
                                : clientConfig != null ? new ChromeDriver(options, clientConfig)
                                : new ChromeDriver(options);

                applyPostStart(driver, config.postStart());
                yield driver;
            }
            case "edge" -> {
                EdgeOptions options = buildEdgeOptions(config.capabilities(), fullConfiguration);
                EdgeDriverService service = buildEdgeService(config.service());

                EdgeDriver driver =
                        service != null && clientConfig != null ? new EdgeDriver(service, options, clientConfig)
                                : service != null ? new EdgeDriver(service, options)
                                : clientConfig != null ? new EdgeDriver(options, clientConfig)
                                : new EdgeDriver(options);

                applyPostStart(driver, config.postStart());
                yield driver;
            }
            default -> throw new RuntimeException("Unsupported local browser: " + browserName);
        };
    }

    public static RemoteWebDriver createRemoteDriver(
            String browserName,
            BrowserConfig config,
            ObjectNode fullConfiguration,
            String missingRemoteUrlMessage
    ) throws Exception {
        String remoteUrl = trimToNull(config.connection().get("remoteUrl"));
        if (remoteUrl == null) {
            throw new RuntimeException(missingRemoteUrlMessage);
        }

        MutableCapabilities capabilities = buildRemoteCapabilities(browserName, config.capabilities(), fullConfiguration);
        ClientConfig clientConfig = buildClientConfig(config.connection());
        Boolean enableTracing = toBoolean(config.connection().get("enableTracing"));

        RemoteWebDriver driver =
                clientConfig != null && enableTracing != null ? new RemoteWebDriver(new URL(remoteUrl), capabilities, clientConfig, enableTracing)
                        : clientConfig != null ? new RemoteWebDriver(new URL(remoteUrl), capabilities, clientConfig)
                        : enableTracing != null ? new RemoteWebDriver(new URL(remoteUrl), capabilities, enableTracing)
                        : new RemoteWebDriver(new URL(remoteUrl), capabilities);

        applyPostStart(driver, config.postStart());
        return driver;
    }

    public static MutableCapabilities buildRemoteCapabilities(
            String browserName,
            Map<String, Object> capabilities,
            ObjectNode fullConfiguration
    ) {
        return switch (browserName) {
            case "chrome" -> buildChromeOptions(capabilities, fullConfiguration);
            case "edge" -> buildEdgeOptions(capabilities, fullConfiguration);
            default -> throw new RuntimeException("Unsupported remote browser: " + browserName);
        };
    }

    public static ChromeOptions buildChromeOptions(Map<String, Object> capabilities, ObjectNode fullConfiguration) {
        ChromeOptions options = new ChromeOptions();
        applyCapabilities(options, capabilities);
        applyDebugPort(options, "chrome", fullConfiguration);
        return options;
    }

    public static EdgeOptions buildEdgeOptions(Map<String, Object> capabilities, ObjectNode fullConfiguration) {
        EdgeOptions options = new EdgeOptions();
        applyCapabilities(options, capabilities);
        applyDebugPort(options, "edge", fullConfiguration);
        return options;
    }

    public static void applyCapabilities(MutableCapabilities target, Map<String, Object> capabilities) {
        safeMap(capabilities).forEach((key, value) -> {
            if (key != null && value != null) {
                target.setCapability(key, value);
            }
        });
    }

    public static ChromeDriverService buildChromeService(Map<String, Object> serviceMap) {
        Map<String, Object> service = safeMap(serviceMap);
        if (service.isEmpty()) {
            return null;
        }

        ChromeDriverService.Builder builder = new ChromeDriverService.Builder();
        applyCommonServiceSettings(builder, service);

        if (service.containsKey("appendLog")) {
            builder.withAppendLog(booleanOrDefault(service.get("appendLog"), false));
        }
        if (service.containsKey("buildCheckDisabled")) {
            builder.withBuildCheckDisabled(booleanOrDefault(service.get("buildCheckDisabled"), false));
        }
        if (service.containsKey("verbose")) {
            builder.withVerbose(booleanOrDefault(service.get("verbose"), false));
        }
        if (service.containsKey("silent")) {
            builder.withSilent(booleanOrDefault(service.get("silent"), false));
        }
        if (service.containsKey("allowedListIps")) {
            builder.withAllowedListIps(trimToNull(service.get("allowedListIps")));
        }
        if (service.containsKey("readableTimestamp")) {
            Boolean readableTimestamp = toBoolean(service.get("readableTimestamp"));
            if (readableTimestamp != null) {
                builder.withReadableTimestamp(readableTimestamp);
            }
        }
        if (service.containsKey("logLevel")) {
            ChromiumDriverLogLevel logLevel = parseChromiumLogLevel(service.get("logLevel"));
            if (logLevel != null) {
                builder.withLogLevel(logLevel);
            }
        }

        return builder.build();
    }

    public static EdgeDriverService buildEdgeService(Map<String, Object> serviceMap) {
        Map<String, Object> service = safeMap(serviceMap);
        if (service.isEmpty()) {
            return null;
        }

        EdgeDriverService.Builder builder = new EdgeDriverService.Builder();
        applyCommonServiceSettings(builder, service);

        if (service.containsKey("appendLog")) {
            builder.withAppendLog(booleanOrDefault(service.get("appendLog"), false));
        }
        if (service.containsKey("buildCheckDisabled")) {
            builder.withBuildCheckDisabled(booleanOrDefault(service.get("buildCheckDisabled"), false));
        }
        if (service.containsKey("verbose")) {
            builder.withVerbose(booleanOrDefault(service.get("verbose"), false));
        }
        if (service.containsKey("silent")) {
            builder.withSilent(booleanOrDefault(service.get("silent"), false));
        }
        if (service.containsKey("allowedListIps")) {
            builder.withAllowedListIps(trimToNull(service.get("allowedListIps")));
        }
        if (service.containsKey("readableTimestamp")) {
            Boolean readableTimestamp = toBoolean(service.get("readableTimestamp"));
            if (readableTimestamp != null) {
                builder.withReadableTimestamp(readableTimestamp);
            }
        }
        if (service.containsKey("logLevel")) {
            ChromiumDriverLogLevel logLevel = parseChromiumLogLevel(service.get("logLevel"));
            if (logLevel != null) {
                builder.withLoglevel(logLevel);
            }
        }

        return builder.build();
    }

    public static <DS extends DriverService, B extends DriverService.Builder<DS, B>> void applyCommonServiceSettings(
            B builder,
            Map<String, Object> service
    ) {
        String executable = trimToNull(service.get("driverExecutable"));
        if (executable != null) {
            builder.usingDriverExecutable(new File(executable));
        }

        Integer port = toInteger(service.get("port"));
        if (port != null) {
            if (port <= 0) {
                builder.usingAnyFreePort();
            } else {
                builder.usingPort(port);
            }
        }

        Long timeoutMs = toLong(service.get("timeoutMs"));
        if (timeoutMs != null && timeoutMs >= 0) {
            builder.withTimeout(Duration.ofMillis(timeoutMs));
        }

        String logFile = trimToNull(service.get("logFile"));
        if (logFile != null) {
            builder.withLogFile(new File(logFile));
        }

        OutputStream logOutput = resolveLogOutput(service.get("logOutput"));
        if (logOutput != null) {
            builder.withLogOutput(logOutput);
        }

        Map<String, String> environment = toStringMap(service.get("environment"));
        if (!environment.isEmpty()) {
            builder.withEnvironment(environment);
        }
    }

    public static ClientConfig buildClientConfig(Map<String, Object> connectionSection) throws Exception {
        Map<String, Object> client = safeMap(safeMap(connectionSection).get("clientConfig"));
        if (client.isEmpty()) {
            return null;
        }

        ClientConfig config = ClientConfig.defaultConfig();

        String baseUrl = trimToNull(client.get("baseUrl"));
        if (baseUrl != null) {
            config = config.baseUrl(new URL(baseUrl));
        }

        Long connectionTimeoutMs = toLong(client.get("connectionTimeoutMs"));
        if (connectionTimeoutMs != null && connectionTimeoutMs >= 0) {
            config = config.connectionTimeout(Duration.ofMillis(connectionTimeoutMs));
        }

        Long readTimeoutMs = toLong(client.get("readTimeoutMs"));
        if (readTimeoutMs != null && readTimeoutMs >= 0) {
            config = config.readTimeout(Duration.ofMillis(readTimeoutMs));
        }

        Long wsTimeoutMs = toLong(client.get("wsTimeoutMs"));
        if (wsTimeoutMs != null && wsTimeoutMs >= 0) {
            config = config.wsTimeout(Duration.ofMillis(wsTimeoutMs));
        }

        String version = trimToNull(client.get("version"));
        if (version != null) {
            config = config.version(version);
        }

        if (booleanOrDefault(client.get("withRetries"), false)) {
            config = config.withRetries();
        }

        return config;
    }

    public static void applyPostStart(WebDriver driver, Map<String, Object> postStartSection) {
        Map<String, Object> postStart = safeMap(postStartSection);
        if (postStart.isEmpty()) {
            return;
        }

        String window = trimToNull(postStart.get("window"));
        if (window != null) {
            switch (window.toLowerCase(Locale.ROOT)) {
                case "maximize" -> driver.manage().window().maximize();
                case "minimize" -> driver.manage().window().minimize();
                case "fullscreen" -> driver.manage().window().fullscreen();
                default -> {
                }
            }
        }

        if (booleanOrDefault(postStart.get("clearCookies"), false)) {
            driver.manage().deleteAllCookies();
        }

        Long implicitWaitMs = toLong(postStart.get("implicitWaitMs"));
        if (implicitWaitMs != null && implicitWaitMs >= 0) {
            driver.manage().timeouts().implicitlyWait(Duration.ofMillis(implicitWaitMs));
        }

        Long pageLoadTimeoutMs = toLong(postStart.get("pageLoadTimeoutMs"));
        if (pageLoadTimeoutMs != null && pageLoadTimeoutMs >= 0) {
            driver.manage().timeouts().pageLoadTimeout(Duration.ofMillis(pageLoadTimeoutMs));
        }

        Long scriptTimeoutMs = toLong(postStart.get("scriptTimeoutMs"));
        if (scriptTimeoutMs != null && scriptTimeoutMs >= 0) {
            driver.manage().timeouts().scriptTimeout(Duration.ofMillis(scriptTimeoutMs));
        }

        if (booleanOrDefault(postStart.get("localFileDetector"), false) && driver instanceof RemoteWebDriver remote) {
            remote.setFileDetector(new LocalFileDetector());
        }

        String initialUrl = trimToNull(postStart.get("initialUrl"));
        if (initialUrl != null) {
            driver.get(initialUrl);
        }
    }

    public static String resolveBrowserName(ObjectNode fullConfiguration, BrowserConfig config) {
        String browserName = trimToNull(fullConfiguration.path("browser").asText(null));
        if (browserName == null) {
            browserName = trimToNull(fullConfiguration.path("browserName").asText(null));
        }
        if (browserName == null) {
            browserName = trimToNull(fullConfiguration.path("metadata").path("browser").asText(null));
        }
        if (browserName == null) {
            browserName = trimToNull(config.capabilities().get("browserName"));
        }
        return normalizeBrowserName(browserName);
    }

    public static boolean hasRemoteUrl(BrowserConfig config) {
        return trimToNull(config.connection().get("remoteUrl")) != null;
    }

    public static void applyDebugPort(ChromeOptions options, String browserName, ObjectNode fullConfiguration) {
        if (!getCurrentScenarioState().debugBrowser) {
            return;
        }

        Integer debugPort = resolveDebugPort(fullConfiguration);
        if (debugPort != null) {
            options.addArguments("--remote-debugging-port=" + debugPort);
        } else {
            ensureDevToolsPort(options, browserName);
        }
    }

    public static void applyDebugPort(EdgeOptions options, String browserName, ObjectNode fullConfiguration) {
        if (!getCurrentScenarioState().debugBrowser) {
            return;
        }

        Integer debugPort = resolveDebugPort(fullConfiguration);
        if (debugPort != null) {
            options.addArguments("--remote-debugging-port=" + debugPort);
        } else {
            ensureDevToolsPort(options, browserName);
        }
    }

    public static Integer resolveDebugPort(ObjectNode fullConfiguration) {
        if (fullConfiguration == null) {
            return null;
        }

        String pathKey = trimToNull(fullConfiguration.path("_pathKey").asText(null));
        if (pathKey == null) {
            return null;
        }

        return 20000 + Math.floorMod(pathKey.toLowerCase(Locale.ROOT).hashCode(), 20000);
    }

    public static ObjectNode requireConfiguration(ObjectNode configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Driver configuration ObjectNode cannot be null");
        }
        return configuration.deepCopy();
    }

    public static Map<String, Object> toRawMap(ObjectNode configuration) {
        return MAPPER.convertValue(configuration, new TypeReference<>() {});
    }

    public static String normalizeBrowserName(String browserName) {
        return browserName == null ? "chrome" : browserName.trim().toLowerCase(Locale.ROOT);
    }

    public static OutputStream resolveLogOutput(Object value) {
        String token = trimToNull(value);
        if (token == null) {
            return null;
        }
        if ("stdout".equalsIgnoreCase(token)) {
            return System.out;
        }
        if ("stderr".equalsIgnoreCase(token)) {
            return System.err;
        }
        return null;
    }

    public static ChromiumDriverLogLevel parseChromiumLogLevel(Object value) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }

        try {
            return ChromiumDriverLogLevel.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public static Integer toInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static Boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    public static boolean booleanOrDefault(Object value, boolean defaultValue) {
        Boolean parsed = toBoolean(value);
        return parsed == null ? defaultValue : parsed;
    }

    public static Map<String, Object> safeMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    public static Map<String, String> toStringMap(Object value) {
        Map<String, Object> raw = safeMap(value);
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null && v != null) {
                result.put(k, String.valueOf(v));
            }
        });
        return result;
    }

    public record BrowserConfig(
            Map<String, Object> connection,
            Map<String, Object> capabilities,
            Map<String, Object> service,
            Map<String, Object> postStart
    ) {
        public static BrowserConfig from(Map<String, Object> rawMap) {
            Map<String, Object> raw = rawMap == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rawMap);

            boolean structured = raw.keySet().stream().anyMatch(RESERVED_ROOT_KEYS::contains);

            if (structured) {
                Map<String, Object> connection = safeMap(raw.get("connection"));
                Map<String, Object> capabilities = safeMap(raw.get("capabilities"));
                Map<String, Object> service = safeMap(raw.get("service"));
                Map<String, Object> postStart = safeMap(raw.get("postStart"));

                if (capabilities.isEmpty()) {
                    raw.forEach((k, v) -> {
                        if (!RESERVED_ROOT_KEYS.contains(k) && !"remoteUrl".equals(k)) {
                            capabilities.put(k, v);
                        }
                    });
                }

                if (!connection.containsKey("remoteUrl") && raw.containsKey("remoteUrl")) {
                    connection.put("remoteUrl", raw.get("remoteUrl"));
                }

                return new BrowserConfig(connection, capabilities, service, postStart);
            }

            Map<String, Object> connection = new LinkedHashMap<>();
            Map<String, Object> capabilities = new LinkedHashMap<>(raw);

            if (capabilities.containsKey("remoteUrl")) {
                connection.put("remoteUrl", capabilities.remove("remoteUrl"));
            }

            return new BrowserConfig(
                    connection,
                    capabilities,
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>()
            );
        }
    }
}