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

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.domoperations.SeleniumUtils.ensureDevToolsPort;
import static tools.dscode.common.mappings.BracketLiteralMasker.resolveFromDocStringOrConfig;
import static tools.dscode.common.mappings.custommappings.TildeReader.tildeReader;
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
        String browserName = String.valueOf(resolveVarOrDefault("pkb_BROWSER", "BROWSER"));
        System.out.println("@@resolved-browserName: " + browserName + "");
        return getDriver(browserName);
    }

    public static WebDriver getDriver(String browserName) {
        WebDriver webDriver = getBrowserOrDefaultToChrome(browserName);
        getRunningStep().webDriverUsed = webDriver;
        return webDriver;
    }

    private static WebDriver getBrowserOrDefaultToChrome(String browserName) {
        return (WebDriver) getObjectFromRegistryOrDefault(browserName, "CHROME");
    }

    @Given(objRegistration + "BROWSER$")
    public WebDriver getBrowser() throws Exception {
        System.out.println("@@browser!");
        return getLocalBrowser("chrome", "");
    }

    @Given(objRegistration + "(CHROME|EDGE)(.*)?$")
    public WebDriver getLocalBrowser(String browserName, String configFileSuffix) throws Exception {
        System.out.println("@@1 browserName: " + browserName + " configFileSuffix: " + configFileSuffix);
        String normalizedBrowserName = normalizeBrowserName(browserName);
        BrowserConfig config = loadBrowserConfig(
                normalizedBrowserName + safeSuffix(configFileSuffix),
                browserName + " Driver Configuration not found"
        );
        return createLocalDriver(normalizedBrowserName, config);
    }

    @Given(objRegistration + "(SAUCE_)(chrome|edge)(.*)?$")
    public WebDriver getSauceLabs(String saucePrefix, String browserName, String configFileSuffix) throws Exception {
        System.out.println("@@2 browserName: " + browserName + " configFileSuffix: " + configFileSuffix);
        String normalizedBrowserName = normalizeBrowserName(browserName);
        BrowserConfig config = loadBrowserConfig(
                saucePrefix + normalizedBrowserName + safeSuffix(configFileSuffix),
                "Sauce Labs Driver Configuration not found"
        );
        return createRemoteDriver(normalizedBrowserName, config, "Sauce Labs remoteUrl not found");
    }

    @Given(objRegistration + "(GRID_)(chrome|edge)(.*)?$")
    @Given(objRegistration + "(SELENIUM_GRID_)(chrome|edge)(.*)?$")
    public WebDriver getSeleniumGrid(String gridPrefix , String browserName, String configFileSuffix) throws Exception {
        String normalizedBrowserName = normalizeBrowserName(browserName);
        BrowserConfig config = loadBrowserConfig(
                gridPrefix + normalizedBrowserName + safeSuffix(configFileSuffix),
                "Selenium Grid Driver Configuration not found"
        );
        return createRemoteDriver(normalizedBrowserName, config, "Selenium Grid remoteUrl not found");
    }

    private static WebDriver createLocalDriver(String browserName, BrowserConfig config) throws Exception {
        ClientConfig clientConfig = buildClientConfig(config.connection());

        return switch (browserName) {
            case "chrome" -> {
                ChromeOptions options = buildChromeOptions(config.capabilities());
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
                EdgeOptions options = buildEdgeOptions(config.capabilities());
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

    public static WebDriver createRemoteDriver(String browserName, BrowserConfig config, String missingRemoteUrlMessage) throws Exception {
        String remoteUrl = trimToNull(config.connection().get("remoteUrl"));
        if (remoteUrl == null) {
            throw new RuntimeException(missingRemoteUrlMessage);
        }

        MutableCapabilities capabilities = buildRemoteCapabilities(browserName, config.capabilities());
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

    public static MutableCapabilities buildRemoteCapabilities(String browserName, Map<String, Object> capabilities) {
        return switch (browserName) {
            case "chrome" -> buildChromeOptions(capabilities);
            case "edge" -> buildEdgeOptions(capabilities);
            default -> throw new RuntimeException("Unsupported remote browser: " + browserName);
        };
    }

    public static ChromeOptions buildChromeOptions(Map<String, Object> capabilities) {
        ChromeOptions options = new ChromeOptions();
        applyCapabilities(options, capabilities);
        if (getCurrentScenarioState().debugBrowser) {
            ensureDevToolsPort(options, "chrome");
        }
        return options;
    }

    public static EdgeOptions buildEdgeOptions(Map<String, Object> capabilities) {
        EdgeOptions options = new EdgeOptions();
        applyCapabilities(options, capabilities);
        if (getCurrentScenarioState().debugBrowser) {
            ensureDevToolsPort(options, "edge");
        }
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

    public static <DS extends DriverService, B extends DriverService.Builder<DS, B>> void applyCommonServiceSettings(B builder, Map<String, Object> service) {
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

    public static BrowserConfig loadBrowserConfig(String configKey, String missingMessage) throws Exception {
        System.out.println("@@loadBrowserConfig: configKey: " + configKey + " , missingMessage: " + missingMessage + "");
        String json = resolveFromDocStringOrConfig(configKey);
        System.out.println("@@json: " + json + "");
        if (Objects.isNull(json)) {
            throw new RuntimeException(missingMessage);
        }

        Map<String, Object> raw = tildeReader.read(json, Map.class);
        return BrowserConfig.from(raw);
    }

    public static String normalizeBrowserName(String browserName) {
        return browserName == null ? "chrome" : browserName.trim().toLowerCase(Locale.ROOT);
    }

    public static String safeSuffix(String configFileSuffix) {
        return configFileSuffix == null ? "" : configFileSuffix.trim().toLowerCase(Locale.ROOT);
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

    @SuppressWarnings("unchecked")
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
        static BrowserConfig from(Map<String, Object> rawMap) {
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