package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;
import org.openqa.selenium.JavascriptExecutor;
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
import java.util.Objects;
import java.util.Set;

import static io.cucumber.core.runner.CurrentScenarioState.registerScenarioObject;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.domoperations.SeleniumUtils.ensureDevToolsPort;
import static tools.dscode.common.mappings.BracketLiteralMasker.resolveFromDocStringOrConfig;
import static tools.dscode.common.mappings.NodeMap.MAPPER;
import static tools.dscode.common.variables.RunVars.resolveVarOrDefault;
import static tools.dscode.coredefinitions.ObjectRegistrationSteps.getObjectFromRegistryOrDefault;
import static tools.dscode.coredefinitions.ObjectRegistrationSteps.objRegistration;

public class BrowserSteps {

    private static final Set<String> RESERVED_ROOT_KEYS = Set.of(
            "connection",
            "capabilities",
            "service",
            "postStart",
            "metadata",
            "framework"
    );

    public static JavascriptExecutor getJavascriptExecutor() {
        return (JavascriptExecutor) getDefaultDriver();
    }

    public static WebDriver getDefaultDriver() {
        String browserName = resolveVarOrDefault("BROWSER", "BROWSER").toString();
        return getDriver(browserName);
    }

    public static WebDriver getDriver(String browserName) {
        WebDriver webDriver = getBrowserOrDefaultToChrome(browserName);
        getRunningStep().webDriverUsed = webDriver;
        return (WebDriver) webDriver;
    }

    private static WebDriver getBrowserOrDefaultToChrome(String browserName) {
        return (WebDriver) getObjectFromRegistryOrDefault(browserName, "CHROME");
    }

    @Given(objRegistration + "BROWSER$")
    public WebDriver getBrowser() throws Exception {
        return getLocalBrowser("chrome", "");
    }

    @Given(objRegistration + "(CHROME|EDGE)(.*)?$")
    public WebDriver getLocalBrowser(String browserName, String configFileSuffix) throws Exception {
        String normalizedBrowserName = normalizeBrowserName(browserName);
        DriverProfile profile = loadDriverProfile(
                normalizedBrowserName + safeSuffix(configFileSuffix),
                browserName + " Driver Configuration not found"
        );

        WebDriver driver = createLocalDriver(normalizedBrowserName, profile);
        registerDriverAndProfile(driver, profile);
        return driver;
    }

    @Given(objRegistration + "SAUCE_(chrome|edge)(.*)?$")
    public WebDriver getSauceLabs(String browserName, String configFileSuffix) throws Exception {
        String normalizedBrowserName = normalizeBrowserName(browserName);
        DriverProfile profile = loadDriverProfile(
                normalizedBrowserName + safeSuffix(configFileSuffix),
                "Sauce Labs Driver Configuration not found"
        );

        WebDriver driver = createRemoteDriver(normalizedBrowserName, profile, "Sauce Labs remoteUrl not found");
        registerDriverAndProfile(driver, profile);
        return driver;
    }

    @Given(objRegistration + "GRID_(chrome|edge)(.*)?$")
    @Given(objRegistration + "SELENIUM_GRID_(chrome|edge)(.*)?$")
    public WebDriver getSeleniumGrid(String browserName, String configFileSuffix) throws Exception {
        String normalizedBrowserName = normalizeBrowserName(browserName);
        DriverProfile profile = loadDriverProfile(
                normalizedBrowserName + safeSuffix(configFileSuffix),
                "Selenium Grid Driver Configuration not found"
        );

        WebDriver driver = createRemoteDriver(normalizedBrowserName, profile, "Selenium Grid remoteUrl not found");
        registerDriverAndProfile(driver, profile);
        return driver;
    }

    private static void registerDriverAndProfile(WebDriver driver, DriverProfile profile) {
        registerScenarioObject("browser", driver);
        registerScenarioObject("browserConfig", profile.raw());
        registerScenarioObject("browserConfigMetadata", profile.metadata());
        registerScenarioObject("browserConfigPostStart", profile.postStart());
    }

    private static WebDriver createLocalDriver(String browserName, DriverProfile profile) throws Exception {
        ClientConfig clientConfig = buildClientConfig(profile.connection());

        switch (browserName) {
            case "chrome": {
                ChromeOptions options = buildChromeOptions(profile.capabilities());
                ChromeDriverService service = buildChromeService(profile.service());

                ChromeDriver driver;
                if (service != null && clientConfig != null) {
                    driver = new ChromeDriver(service, options, clientConfig);
                } else if (service != null) {
                    driver = new ChromeDriver(service, options);
                } else if (clientConfig != null) {
                    driver = new ChromeDriver(options, clientConfig);
                } else {
                    driver = new ChromeDriver(options);
                }

                applyPostStart(driver, profile.postStart());
                return driver;
            }

            case "edge": {
                EdgeOptions options = buildEdgeOptions(profile.capabilities());
                EdgeDriverService service = buildEdgeService(profile.service());

                EdgeDriver driver;
                if (service != null && clientConfig != null) {
                    driver = new EdgeDriver(service, options, clientConfig);
                } else if (service != null) {
                    driver = new EdgeDriver(service, options);
                } else if (clientConfig != null) {
                    driver = new EdgeDriver(options, clientConfig);
                } else {
                    driver = new EdgeDriver(options);
                }

                applyPostStart(driver, profile.postStart());
                return driver;
            }

            default:
                throw new RuntimeException("Unsupported local browser: " + browserName);
        }
    }

    private static WebDriver createRemoteDriver(String browserName, DriverProfile profile, String missingRemoteUrlMessage) throws Exception {
        String remoteUrl = trimToNull(profile.connection().get("remoteUrl"));
        if (remoteUrl == null) {
            throw new RuntimeException(missingRemoteUrlMessage);
        }

        MutableCapabilities options = buildRemoteOptions(browserName, profile.capabilities());
        ClientConfig clientConfig = buildClientConfig(profile.connection());
        Boolean enableTracing = toBoolean(profile.connection().get("enableTracing"));

        WebDriver driver;
        URL url = new URL(remoteUrl);

        if (clientConfig != null && enableTracing != null) {
            driver = new RemoteWebDriver(url, options, clientConfig, enableTracing);
        } else if (clientConfig != null) {
            driver = new RemoteWebDriver(url, options, clientConfig);
        } else if (enableTracing != null) {
            driver = new RemoteWebDriver(url, options, enableTracing);
        } else {
            driver = new RemoteWebDriver(url, options);
        }

        applyPostStart(driver, profile.postStart());
        return driver;
    }

    private static MutableCapabilities buildRemoteOptions(String browserName, Map<String, Object> capabilities) {
        switch (browserName) {
            case "chrome":
                return buildChromeOptions(capabilities);
            case "edge":
                return buildEdgeOptions(capabilities);
            default:
                throw new RuntimeException("Unsupported remote browser: " + browserName);
        }
    }

    private static ChromeOptions buildChromeOptions(Map<String, Object> capabilities) {
        ChromeOptions options = new ChromeOptions();
        applyCapabilities(options, capabilities);
        if (getCurrentScenarioState().debugBrowser) {
            ensureDevToolsPort(options, "chrome");
        }
        return options;
    }

    private static EdgeOptions buildEdgeOptions(Map<String, Object> capabilities) {
        EdgeOptions options = new EdgeOptions();
        applyCapabilities(options, capabilities);
        if (getCurrentScenarioState().debugBrowser) {
            ensureDevToolsPort(options, "edge");
        }
        return options;
    }

    private static void applyCapabilities(MutableCapabilities options, Map<String, Object> capabilities) {
        safeMap(capabilities).forEach((key, value) -> {
            if (key != null && value != null) {
                options.setCapability(key, value);
            }
        });
    }

    private static ChromeDriverService buildChromeService(Map<String, Object> serviceMap) {
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
            builder.withReadableTimestamp(toBoolean(service.get("readableTimestamp")));
        }
        if (service.containsKey("logLevel")) {
            ChromiumDriverLogLevel logLevel = parseChromiumLogLevel(service.get("logLevel"));
            if (logLevel != null) {
                builder.withLogLevel(logLevel);
            }
        }

        return builder.build();
    }

    private static EdgeDriverService buildEdgeService(Map<String, Object> serviceMap) {
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
            builder.withReadableTimestamp(toBoolean(service.get("readableTimestamp")));
        }
        if (service.containsKey("logLevel")) {
            ChromiumDriverLogLevel logLevel = parseChromiumLogLevel(service.get("logLevel"));
            if (logLevel != null) {
                builder.withLoglevel(logLevel);
            }
        }

        return builder.build();
    }

    private static <DS extends DriverService, B extends DriverService.Builder<DS, B>> void applyCommonServiceSettings(B builder, Map<String, Object> service) {
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

    private static ClientConfig buildClientConfig(Map<String, Object> connectionSection) throws Exception {
        Map<String, Object> connection = safeMap(connectionSection);
        Map<String, Object> client = safeMap(connection.get("clientConfig"));
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

    private static void applyPostStart(WebDriver driver, Map<String, Object> postStartSection) {
        Map<String, Object> postStart = safeMap(postStartSection);
        if (postStart.isEmpty()) {
            return;
        }

        String window = trimToNull(postStart.get("window"));
        if (window != null) {
            switch (window.toLowerCase(Locale.ROOT)) {
                case "maximize":
                    driver.manage().window().maximize();
                    break;
                case "minimize":
                    driver.manage().window().minimize();
                    break;
                case "fullscreen":
                    driver.manage().window().fullscreen();
                    break;
                default:
                    // ignore unknown window directives
                    break;
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

        if (booleanOrDefault(postStart.get("localFileDetector"), false)) {
            ((RemoteWebDriver)driver).setFileDetector(new LocalFileDetector());
        }

        String initialUrl = trimToNull(postStart.get("initialUrl"));
        if (initialUrl != null) {
            driver.get(initialUrl);
        }
    }

    private static DriverProfile loadDriverProfile(String configKey, String missingMessage) throws Exception {
        String json = resolveFromDocStringOrConfig(configKey);
        if (Objects.isNull(json)) {
            throw new RuntimeException(missingMessage);
        }

        Map<String, Object> raw = MAPPER.readValue(json, Map.class);
        return DriverProfile.from(raw);
    }

    private static String normalizeBrowserName(String browserName) {
        return browserName == null ? "chrome" : browserName.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeSuffix(String configFileSuffix) {
        return configFileSuffix == null ? "" : configFileSuffix;
    }

    private static OutputStream resolveLogOutput(Object value) {
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

    private static ChromiumDriverLogLevel parseChromiumLogLevel(Object value) {
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

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer toInteger(Object value) {
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

    private static Long toLong(Object value) {
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

    private static Boolean toBoolean(Object value) {
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

    private static boolean booleanOrDefault(Object value, boolean defaultValue) {
        Boolean parsed = toBoolean(value);
        return parsed == null ? defaultValue : parsed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static Map<String, String> toStringMap(Object value) {
        Map<String, Object> raw = safeMap(value);
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null && v != null) {
                result.put(k, String.valueOf(v));
            }
        });
        return result;
    }

    private record DriverProfile(
            Map<String, Object> raw,
            Map<String, Object> connection,
            Map<String, Object> capabilities,
            Map<String, Object> service,
            Map<String, Object> postStart,
            Map<String, Object> metadata
    ) {
        static DriverProfile from(Map<String, Object> rawMap) {
            Map<String, Object> raw = rawMap == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rawMap);

            boolean structured = raw.keySet().stream().anyMatch(RESERVED_ROOT_KEYS::contains);

            if (structured) {
                Map<String, Object> connection = safeMap(raw.get("connection"));
                Map<String, Object> capabilities = safeMap(raw.get("capabilities"));
                Map<String, Object> service = safeMap(raw.get("service"));
                Map<String, Object> postStart = safeMap(raw.get("postStart"));

                Map<String, Object> metadata = safeMap(raw.get("metadata"));
                Map<String, Object> framework = safeMap(raw.get("framework"));
                if (!framework.isEmpty()) {
                    metadata.putIfAbsent("framework", framework);
                }

                // Allow partial migration:
                // if "capabilities" is omitted, treat all non-reserved top-level keys as capabilities.
                if (capabilities.isEmpty()) {
                    raw.forEach((k, v) -> {
                        if (!RESERVED_ROOT_KEYS.contains(k) && !"remoteUrl".equals(k)) {
                            capabilities.put(k, v);
                        }
                    });
                }

                // Allow top-level remoteUrl during migration.
                if (!connection.containsKey("remoteUrl") && raw.containsKey("remoteUrl")) {
                    connection.put("remoteUrl", raw.get("remoteUrl"));
                }

                return new DriverProfile(raw, connection, capabilities, service, postStart, metadata);
            }

            // Legacy flat structure:
            // everything except remoteUrl is treated as capabilities.
            Map<String, Object> connection = new LinkedHashMap<>();
            Map<String, Object> capabilities = new LinkedHashMap<>(raw);

            if (capabilities.containsKey("remoteUrl")) {
                connection.put("remoteUrl", capabilities.remove("remoteUrl"));
            }

            return new DriverProfile(
                    raw,
                    connection,
                    capabilities,
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>(),
                    new LinkedHashMap<>()
            );
        }
    }
}