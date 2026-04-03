package tools.dscode.common.driver;

import com.epam.reportportal.utils.IssueUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumDriverLogLevel;
import org.openqa.selenium.chromium.ChromiumOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeDriverService;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.service.DriverService;

import java.io.File;
import java.io.OutputStream;
import java.net.URL;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.common.domoperations.SeleniumUtils.isDevToolsListening;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.reporting.logging.LogForwarder.closestEntryToPhrase;

public final class DriverConstruction {

    private DriverConstruction() {
    }

    private static final Set<String> DRIVER_KEYS = Set.of(
            "connection",
            "service",
            "capabilities",
            "options",
            "providerCapabilities"
    );

    private static final Set<String> CONNECTION_KEYS = Set.of(
            "remoteUrl",
            "enableTracing",
            "clientConfig"
    );

    private static final Set<String> CLIENT_CONFIG_KEYS = Set.of(
            "baseUrl",
            "connectionTimeoutMs",
            "readTimeoutMs",
            "wsTimeoutMs",
            "version",
            "withRetries"
    );

    private static final Set<String> COMMON_OPTIONS_KEYS = Set.of(
            "args",
            "binary",
            "prefs",
            "experimentalOptions",
            "excludeSwitches",
            "mobileEmulation",
            "localState",
            "debuggerAddress",
            "detach"
    );

    private static final Set<String> COMMON_SERVICE_KEYS = Set.of(
            "driverExecutable",
            "port",
            "timeoutMs",
            "logFile",
            "logOutput",
            "environment"
    );

    private static final Set<String> CHROMIUM_SERVICE_KEYS = Set.of(
            "appendLog",
            "buildCheckDisabled",
            "verbose",
            "silent",
            "allowedListIps",
            "readableTimestamp",
            "logLevel"
    );

    public static RemoteWebDriver createDriver(ObjectNode configuration) throws Exception {
        ObjectNode config = requireConfiguration(configuration);
        String browserName = resolveBrowserName(config);
        ObjectNode driver = getDriverSection(config);

        return hasRemoteUrl(driver)
                ? createRemoteDriver(browserName, driver, config, "Driver remoteUrl not found")
                : createLocalDriver(browserName, driver, config);
    }

    public static RemoteWebDriver createLocalDriver(ObjectNode configuration) throws Exception {
        ObjectNode config = requireConfiguration(configuration);
        return createLocalDriver(resolveBrowserName(config), getDriverSection(config), config);
    }

    public static RemoteWebDriver createRemoteDriver(ObjectNode configuration) throws Exception {
        ObjectNode config = requireConfiguration(configuration);
        return createRemoteDriver(resolveBrowserName(config), getDriverSection(config), config, "Driver remoteUrl not found");
    }

    public static RemoteWebDriver createLocalDriver(
            String browserName,
            ObjectNode driverConfig,
            ObjectNode fullConfiguration
    ) throws Exception {
        ClientConfig clientConfig = buildClientConfig(getObject(driverConfig, "connection"));

        return switch (browserName) {
            case "chrome" -> {
                ChromeOptions options = buildChromeOptions(driverConfig, fullConfiguration);
                ChromeDriverService service = buildChromeService(toObjectMap(getObject(driverConfig, "service")));

                yield service != null && clientConfig != null ? new ChromeDriver(service, options, clientConfig)
                        : service != null ? new ChromeDriver(service, options)
                        : clientConfig != null ? new ChromeDriver(options, clientConfig)
                        : new ChromeDriver(options);
            }
            case "edge" -> {
                EdgeOptions options = buildEdgeOptions(driverConfig, fullConfiguration);
                EdgeDriverService service = buildEdgeService(toObjectMap(getObject(driverConfig, "service")));

                yield service != null && clientConfig != null ? new EdgeDriver(service, options, clientConfig)
                        : service != null ? new EdgeDriver(service, options)
                        : clientConfig != null ? new EdgeDriver(options, clientConfig)
                        : new EdgeDriver(options);
            }
            default -> throw new RuntimeException("Unsupported local browser: " + browserName);
        };
    }

    public static RemoteWebDriver createRemoteDriver(
            String browserName,
            ObjectNode driverConfig,
            ObjectNode fullConfiguration,
            String missingRemoteUrlMessage
    ) throws Exception {
        ObjectNode connection = getObject(driverConfig, "connection");
        String remoteUrl = trimToNull(connection.path("remoteUrl").asText(null));
        if (remoteUrl == null) {
            throw new RuntimeException(missingRemoteUrlMessage);
        }

        MutableCapabilities capabilities = buildRemoteCapabilities(browserName, driverConfig, fullConfiguration);
        ClientConfig clientConfig = buildClientConfig(connection);
        Boolean enableTracing = toBoolean(toJavaValue(connection.get("enableTracing")));

        return clientConfig != null && enableTracing != null ? new RemoteWebDriver(new URL(remoteUrl), capabilities, clientConfig, enableTracing)
                : clientConfig != null ? new RemoteWebDriver(new URL(remoteUrl), capabilities, clientConfig)
                : enableTracing != null ? new RemoteWebDriver(new URL(remoteUrl), capabilities, enableTracing)
                : new RemoteWebDriver(new URL(remoteUrl), capabilities);
    }

    public static MutableCapabilities buildRemoteCapabilities(
            String browserName,
            ObjectNode driverConfig,
            ObjectNode fullConfiguration
    ) {
        return switch (browserName) {
            case "chrome" -> buildChromeOptions(driverConfig, fullConfiguration);
            case "edge" -> buildEdgeOptions(driverConfig, fullConfiguration);
            default -> throw new RuntimeException("Unsupported remote browser: " + browserName);
        };
    }

    public static ChromeOptions buildChromeOptions(ObjectNode driverConfig, ObjectNode fullConfiguration) {
        ChromeOptions options = new ChromeOptions();
        applyCapabilities(options, getObject(driverConfig, "capabilities"));
        applyChromiumOptions(options, getObject(driverConfig, "options"), "chrome");
        applyProviderCapabilities(options, getObject(driverConfig, "providerCapabilities"));
        applyDebugPort(options, "chrome", fullConfiguration);
        return options;
    }

    public static EdgeOptions buildEdgeOptions(ObjectNode driverConfig, ObjectNode fullConfiguration) {
        EdgeOptions options = new EdgeOptions();
        applyCapabilities(options, getObject(driverConfig, "capabilities"));
        applyChromiumOptions(options, getObject(driverConfig, "options"), "edge");
        applyProviderCapabilities(options, getObject(driverConfig, "providerCapabilities"));
        applyDebugPort(options, "edge", fullConfiguration);
        return options;
    }

    public static void applyCapabilities(MutableCapabilities target, ObjectNode capabilities) {
        capabilities.fields().forEachRemaining(entry -> {
            Object value = toJavaValue(entry.getValue());
            if (value != null) {
                target.setCapability(entry.getKey(), value);
            }
        });
    }

    public static void applyProviderCapabilities(MutableCapabilities target, ObjectNode providerCapabilities) {
        providerCapabilities.fields().forEachRemaining(entry -> {
            Object value = toJavaValue(entry.getValue());
            if (value != null) {
                target.setCapability(entry.getKey(), value);
            }
        });
    }

    public static void applyChromiumOptions(ChromiumOptions<?> options, ObjectNode optionsConfig, String browserName) {
        validateKeys(optionsConfig, COMMON_OPTIONS_KEYS, "driver.options for " + browserName);

        List<String> args = toStringList(optionsConfig.get("args"), "driver.options.args");
        if (!args.isEmpty()) {
            options.addArguments(args);
        }

        String binary = trimToNull(optionsConfig.path("binary").asText(null));
        if (binary != null) {
            options.setBinary(binary);
        }

        Object prefs = toJavaValue(optionsConfig.get("prefs"));
        if (prefs != null) {
            options.setExperimentalOption("prefs", prefs);
        }

        Object excludeSwitches = toJavaValue(optionsConfig.get("excludeSwitches"));
        if (excludeSwitches != null) {
            options.setExperimentalOption("excludeSwitches", excludeSwitches);
        }

        Object mobileEmulation = toJavaValue(optionsConfig.get("mobileEmulation"));
        if (mobileEmulation != null) {
            options.setExperimentalOption("mobileEmulation", mobileEmulation);
        }

        Object localState = toJavaValue(optionsConfig.get("localState"));
        if (localState != null) {
            options.setExperimentalOption("localState", localState);
        }

        String debuggerAddress = trimToNull(optionsConfig.path("debuggerAddress").asText(null));
        if (debuggerAddress != null) {
            options.setExperimentalOption("debuggerAddress", debuggerAddress);
        }

        Boolean detach = toBoolean(toJavaValue(optionsConfig.get("detach")));
        if (detach != null) {
            options.setExperimentalOption("detach", detach);
        }

        Object experimentalOptions = toJavaValue(optionsConfig.get("experimentalOptions"));
        if (experimentalOptions instanceof Map<?, ?> map) {
            map.forEach((k, v) -> options.setExperimentalOption(String.valueOf(k), v));
        } else if (experimentalOptions != null) {
            throw new RuntimeException("driver.options.experimentalOptions must be an object");
        }
    }

    public static ChromeDriverService buildChromeService(Map<String, Object> serviceMap) {
        Map<String, Object> service = safeMap(serviceMap);
        if (service.isEmpty()) {
            return null;
        }

        validateKeys(service, union(COMMON_SERVICE_KEYS, CHROMIUM_SERVICE_KEYS), "driver.service");

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

        validateKeys(service, union(COMMON_SERVICE_KEYS, CHROMIUM_SERVICE_KEYS), "driver.service");

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

    public static ClientConfig buildClientConfig(ObjectNode connectionSection) throws Exception {
        validateKeys(connectionSection, CONNECTION_KEYS, "driver.connection");

        ObjectNode client = getObject(connectionSection, "clientConfig");
        if (client.isEmpty()) {
            return null;
        }

        validateKeys(client, CLIENT_CONFIG_KEYS, "driver.connection.clientConfig");

        ClientConfig config = ClientConfig.defaultConfig();

        String baseUrl = trimToNull(client.path("baseUrl").asText(null));
        if (baseUrl != null) {
            config = config.baseUrl(new URL(baseUrl));
        }

        Long connectionTimeoutMs = toLong(toJavaValue(client.get("connectionTimeoutMs")));
        if (connectionTimeoutMs != null && connectionTimeoutMs >= 0) {
            config = config.connectionTimeout(Duration.ofMillis(connectionTimeoutMs));
        }

        Long readTimeoutMs = toLong(toJavaValue(client.get("readTimeoutMs")));
        if (readTimeoutMs != null && readTimeoutMs >= 0) {
            config = config.readTimeout(Duration.ofMillis(readTimeoutMs));
        }

        Long wsTimeoutMs = toLong(toJavaValue(client.get("wsTimeoutMs")));
        if (wsTimeoutMs != null && wsTimeoutMs >= 0) {
            config = config.wsTimeout(Duration.ofMillis(wsTimeoutMs));
        }

        String version = trimToNull(client.path("version").asText(null));
        if (version != null) {
            config = config.version(version);
        }

        if (booleanOrDefault(toJavaValue(client.get("withRetries")), false)) {
            config = config.withRetries();
        }

        return config;
    }

    public static String resolveBrowserName(ObjectNode fullConfiguration) {
        String browserName = trimToNull(fullConfiguration.path("browser").asText(null));
        if (browserName == null) {
            browserName = trimToNull(fullConfiguration.path("browserName").asText(null));
        }
        if (browserName == null) {
            browserName = trimToNull(fullConfiguration.path("driver").path("capabilities").path("browserName").asText(null));
        }
        if (browserName == null) {
            browserName = trimToNull(fullConfiguration.path("metadata").path("browser").asText(null));
        }
        return normalizeBrowserName(browserName);
    }

    public static boolean hasRemoteUrl(ObjectNode driverConfig) {
        return trimToNull(driverConfig.path("connection").path("remoteUrl").asText(null)) != null;
    }

    public static void applyDebugPort(ChromiumOptions<?> options, String browserName, ObjectNode fullConfiguration) {
        if (!getCurrentScenarioState().debugBrowser) {
            return;
        }

        Integer debugPort = resolveDebugPort(fullConfiguration);

        if (isDevToolsListening("127.0.0.1", debugPort)) {
            closestEntryToPhrase().info("Attaching to existing " + browserName + " browser on debugging port: " + debugPort);
            options.setExperimentalOption("debuggerAddress", "127.0.0.1:" + debugPort);
        } else {
            closestEntryToPhrase().info("Starting new " + browserName + " browser on debugging port: " + debugPort);
            options.addArguments("--remote-debugging-port=" + debugPort);
        }
    }

    public static Integer resolveDebugPort(ObjectNode fullConfiguration) {
        JsonNode portNode = fullConfiguration.path("debuggingPort");
        if (portNode.isMissingNode()) {
            portNode = fullConfiguration.path("_pathKey");
        }
        if (portNode.isMissingNode()) {
            portNode = fullConfiguration.path("browser");
        }
        if (portNode.isMissingNode()) {
            throw new RuntimeException("Failed to set debugging port. Cannot find debuggingPort, _pathKey, or browser.");
        }

        String token = trimToNull(portNode.asText());
        return 9000 + (Math.abs(token.toLowerCase(Locale.ROOT).hashCode()) % 1000);
    }

    public static ObjectNode requireConfiguration(ObjectNode configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("Driver configuration ObjectNode cannot be null");
        }
        return configuration.deepCopy();
    }

    public static ObjectNode getDriverSection(ObjectNode configuration) {
        JsonNode driverNode = configuration.get("driver");
        if (driverNode == null || driverNode.isNull()) {
            return getLegacyDriverSection(configuration);
        }
        if (!driverNode.isObject()) {
            throw new RuntimeException("Top-level 'driver' must be an object");
        }

        ObjectNode driver = ((ObjectNode) driverNode).deepCopy();
        validateKeys(driver, DRIVER_KEYS, "driver");
        return driver;
    }

    public static ObjectNode getLegacyDriverSection(ObjectNode configuration) {
        ObjectNode driver = MAPPER.createObjectNode();

        copyObject(configuration, driver, "connection");
        copyObject(configuration, driver, "service");
        copyObject(configuration, driver, "capabilities");
        copyObject(configuration, driver, "options");
        copyObject(configuration, driver, "providerCapabilities");

        if (configuration.has("remoteUrl")) {
            ObjectNode connection = getObject(driver, "connection");
            connection.putIfAbsent("remoteUrl", configuration.get("remoteUrl"));
            driver.set("connection", connection);
        }

        validateKeys(driver, DRIVER_KEYS, "driver");
        return driver;
    }

    public static void copyObject(ObjectNode source, ObjectNode target, String fieldName) {
        JsonNode node = source.get(fieldName);
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isObject()) {
            throw new RuntimeException("Top-level '" + fieldName + "' must be an object");
        }
        target.set(fieldName, node.deepCopy());
    }

    public static ObjectNode getObject(ObjectNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            return MAPPER.createObjectNode();
        }
        if (!node.isObject()) {
            throw new RuntimeException("Expected object at '" + fieldName + "'");
        }
        return (ObjectNode) node;
    }

    public static Object toJavaValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return MAPPER.convertValue(node, Object.class);
    }

    public static List<String> toStringList(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new RuntimeException(path + " must be an array");
        }

        return MAPPER.convertValue(node, MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    public static Map<String, Object> toObjectMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new LinkedHashMap<>();
        }
        if (!node.isObject()) {
            throw new RuntimeException("Expected object but got: " + node.getNodeType());
        }
        return MAPPER.convertValue(node, MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
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

    public static void validateKeys(ObjectNode node, Set<String> allowedKeys, String sectionName) {
        node.fieldNames().forEachRemaining(key -> {
            if (!allowedKeys.contains(key)) {
                throw new RuntimeException("Unsupported property '" + key + "' in " + sectionName);
            }
        });
    }

    public static void validateKeys(Map<String, Object> map, Set<String> allowedKeys, String sectionName) {
        map.keySet().forEach(key -> {
            if (!allowedKeys.contains(key)) {
                throw new RuntimeException("Unsupported property '" + key + "' in " + sectionName);
            }
        });
    }

    public static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new java.util.LinkedHashSet<>(left);
        result.addAll(right);
        return result;
    }
}