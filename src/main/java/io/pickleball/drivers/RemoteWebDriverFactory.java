package io.pickleball.drivers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pickleball.cacheandstate.MethodCache;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.Point;
import org.openqa.selenium.Proxy;
import org.openqa.selenium.UsernameAndPassword;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeDriverService;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.RemoteWebDriverBuilder;
import org.openqa.selenium.remote.UselessFileDetector;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.service.DriverService;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;

import static io.pickleball.cacheandstate.GlobalCache.getGlobalConfigs;

public class RemoteWebDriverFactory {

    public static void main(String[] args) {
        System.out.println("@@getGlobalConfigs:  " + getGlobalConfigs());
        System.out.println("@@driverconfigs:  " + getGlobalConfigs().get("configs.driverconfigs"));
    }

    private static final ObjectMapper mapper = new ObjectMapper();

    public static WebDriver createDriver(ObjectNode yamlConfig) throws Exception {
        if (yamlConfig == null || !yamlConfig.fieldNames().hasNext()) {
            throw new IllegalArgumentException("Invalid YAML configuration: missing top-level property");
        }

        String topLevelKey = yamlConfig.fieldNames().next();
        JsonNode configNode = yamlConfig.get(topLevelKey);
        if (!(configNode instanceof ObjectNode)) {
            throw new IllegalArgumentException("Invalid YAML configuration for: " + topLevelKey);
        }

        Class<?> driverClass = switch (topLevelKey) {
            case "ChromeDriver" -> ChromeDriver.class;
            case "EdgeDriver" -> EdgeDriver.class;
            case "RemoteWebDriver" -> RemoteWebDriver.class;
            default -> throw new IllegalArgumentException("Unknown driver type: " + topLevelKey);
        };

        return instantiateDriver(driverClass, (ObjectNode) configNode);
    }

    private static WebDriver instantiateDriver(Class<?> driverClass, ObjectNode configNode) throws Exception {
        if (driverClass.equals(ChromeDriver.class)) {
            ChromeDriverService service = (ChromeDriverService) configureDriverService(
                    configNode.get("ChromeDriverService"), () -> new ChromeDriverService.Builder());
            ChromeOptions options = configureObject(new ChromeOptions(), configNode.get("ChromeOptions"));
            ClientConfig clientConfig = configureObject(ClientConfig.defaultConfig(), configNode.get("ClientConfig"));
            configureObject(options, configNode.get("MutableCapabilities"));
            ChromeDriver driver = new ChromeDriver(service, options, clientConfig);
            configureObject(driver, configNode.get("RemoteWebDriver"));
            return driver;
        } else if (driverClass.equals(EdgeDriver.class)) {
            EdgeDriverService service = (EdgeDriverService) configureDriverService(
                    configNode.get("EdgeDriverService"), () -> new EdgeDriverService.Builder());
            EdgeOptions options = configureObject(new EdgeOptions(), configNode.get("EdgeOptions"));
            ClientConfig clientConfig = configureObject(ClientConfig.defaultConfig(), configNode.get("ClientConfig"));
            configureObject(options, configNode.get("MutableCapabilities"));
            EdgeDriver driver = new EdgeDriver(service, options, clientConfig);
            configureObject(driver, configNode.get("RemoteWebDriver"));
            return driver;
        } else if (driverClass.equals(RemoteWebDriver.class)) {
            RemoteWebDriverBuilder builder = RemoteWebDriver.builder();
            configureObject(builder, configNode.get("RemoteWebDriverBuilder"));
            configureObject(builder, configNode.get("MutableCapabilities"));
            RemoteWebDriver driver = (RemoteWebDriver) builder.build();
            configureObject(driver, configNode.get("RemoteWebDriver"));
            return driver;
        }
        throw new IllegalArgumentException("Unsupported driver class: " + driverClass.getName());
    }

    private static DriverService configureDriverService(
            JsonNode serviceNode, Supplier<? extends DriverService.Builder> builderSupplier) throws Exception {
        if (!(serviceNode instanceof ObjectNode)) return builderSupplier.get().build();
        DriverService.Builder builder = builderSupplier.get();
        configureObject(builder, serviceNode);
        return builder.build();
    }

    private static <T> T configureObject(T instance, JsonNode configNode) throws Exception {
        if (configNode == null || instance == null || !(configNode instanceof ObjectNode)) return instance;
        ObjectNode objectNode = (ObjectNode) configNode;
        Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String propName = entry.getKey();
            JsonNode valueNode = entry.getValue();
            try {
                Method method = MethodCache.getMethod(instance.getClass(), propName, new Class<?>[]{Object.class});
                Class<?> paramType = method.getParameterTypes()[0];
                if (method.getReturnType() != void.class && method.getParameterCount() == 0) {
                    Object nestedInstance = method.invoke(instance);
                    configureObject(nestedInstance, valueNode);
                } else {
                    Object value = parseValue(valueNode, propName, paramType);
                    invokeMethod(instance, propName, value);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid method: " + propName + " in " + instance.getClass().getName(), e);
            }
        }
        return instance;
    }

    private static Object parseValue(JsonNode node, String propName, Class<?> paramType) throws Exception {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            if (paramType.equals(Map.class)) return mapper.convertValue(node, Map.class);
            if (paramType.equals(Proxy.class)) return parseProxy(node);
            if (paramType.equals(UsernameAndPassword.class)) return parseUsernameAndPassword(node);
            if (paramType.equals(Dimension.class)) return parseDimension(node);
            if (paramType.equals(Point.class)) return parsePoint(node);
            throw new IllegalArgumentException("Unsupported object type: " + paramType.getName());
        }
        if (node.isArray()) return mapper.convertValue(node, List.class);
        if (node.isTextual()) {
            String text = node.asText();
            if (paramType.isEnum()) return Enum.valueOf((Class<? extends Enum>) paramType, text.replace(paramType.getSimpleName() + ".", ""));
            if (paramType.equals(Duration.class)) return Duration.parse(text);
            if (paramType.equals(URI.class)) return URI.create(text);
            if (paramType.equals(File.class)) return new File(text);
            if (paramType.equals(Level.class)) return Level.parse(text);
            if (paramType.equals(UselessFileDetector.class)) return text.equals("UselessFileDetector") ? new UselessFileDetector() : null;
            return text;
        }
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.asInt();
        throw new IllegalArgumentException("Unsupported value type for: " + propName);
    }

    private static Proxy parseProxy(JsonNode node) throws Exception {
        Proxy proxy = new Proxy();
        configureObject(proxy, node);
        return proxy;
    }

    private static UsernameAndPassword parseUsernameAndPassword(JsonNode node) throws Exception {
        UsernameAndPassword credentials = new UsernameAndPassword("", "");
        configureObject(credentials, node);
        return credentials;
    }

    private static Dimension parseDimension(JsonNode node) throws Exception {
        Dimension dimension = new Dimension(0, 0);
        configureObject(dimension, node);
        return dimension;
    }

    private static Point parsePoint(JsonNode node) throws Exception {
        Point point = new Point(0, 0);
        configureObject(point, node);
        return point;
    }

    private static void invokeMethod(Object instance, String methodName, Object... args) throws Exception {
        if (args.length == 1 && args[0] instanceof Map map) {
            map.forEach((key, value) -> {
                try {
                    invokeMethod(instance, methodName, key, value);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to invoke " + methodName + " with key: " + key, e);
                }
            });
            return;
        }
        Class<?>[] paramTypes = Arrays.stream(args)
                .map(arg -> arg != null ? arg.getClass() : Object.class)
                .toArray(Class[]::new);
        try {
            Method method = MethodCache.getMethod(instance.getClass(), methodName, paramTypes);
            method.invoke(instance, args);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    String.format("Method '%s' not found in %s with parameters %s",
                            methodName, instance.getClass().getName(), Arrays.toString(paramTypes)), e);
        }
    }
}