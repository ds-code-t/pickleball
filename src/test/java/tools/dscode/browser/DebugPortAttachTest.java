package tools.dscode.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.cucumber.core.runner.CurrentScenarioState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.common.agentcontrol.AgentDriverHooks;
import tools.dscode.common.driver.DriverConstruction;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.engine.context.ExecutionContext;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

/**
 * Production attach path. A listening debug port is claimed by {@code applyDebugPort}.
 * A browser this process only starts is not claimed. No browser is downloaded.
 */
class DebugPortAttachTest {
    @AfterEach
    void reset() {
        CurrentScenarioState.globalDebugBrowser = false;
        CurrentScenarioState.currentScenarioState.remove();
        DebugPortOwner.resetForTests();
        MappingProcessor.resetCommonMaps();
        while (ExecutionContext.current() != null) {
            ExecutionContext.current().close();
        }
    }

    @Test
    void listeningAttachClaimsThePortAndASecondOwnerFails() throws Exception {
        reset();
        ObjectNode config = config("debug-port-attach");
        int port = DriverConstruction.resolveDebugPort(config);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/json/version", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path session = Path.of(".pickleball").resolve("workbench").resolve("cli-session.json");
        byte[] previous = Files.isRegularFile(session) ? Files.readAllBytes(session) : null;
        try {
            Files.createDirectories(session.getParent());
            Files.writeString(session, "{\"pid\":7,\"token\":\"keep\"}");
            CurrentScenarioState.globalDebugBrowser = true;
            ChromeOptions options = new ChromeOptions();
            DriverConstruction.applyDebugPort(options, "chrome", config);
            assertEquals("127.0.0.1:" + port, debuggerAddress(options));
            assertTrue(DebugPortOwner.ports().contains(port + " driver"), DebugPortOwner.ports().toString());
            String recorded = Files.readString(session);
            assertTrue(recorded.contains(Integer.toString(port)), recorded);
            assertTrue(recorded.contains("driver"), recorded);
            assertEquals(7, new ObjectMapper().readTree(recorded).path("pid").asInt());

            ChromeOptions retry = new ChromeOptions();
            DriverConstruction.applyDebugPort(retry, "chrome", config);
            assertEquals("127.0.0.1:" + port, debuggerAddress(retry));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> PlaywrightConnection.connect("http://127.0.0.1:" + port));
            assertTrue(failure.getMessage().contains("error:"), failure.getMessage());
            assertTrue(failure.getMessage().contains("fix:"), failure.getMessage());
            assertTrue(failure.getMessage().contains(Integer.toString(port)), failure.getMessage());

            WebDriver stub = stubDriver();
            AgentDriverHooks.ready(stub);
            AgentDriverHooks.quitSession(stub);
            assertFalse(DebugPortOwner.ports().contains(port + " driver"), DebugPortOwner.ports().toString());
        } finally {
            server.stop(0);
            if (previous == null) Files.deleteIfExists(session);
            else Files.write(session, previous);
        }
    }

    @Test
    void startingABrowserDoesNotClaimThePort() {
        reset();
        CurrentScenarioState.globalDebugBrowser = true;
        ObjectNode config = config("debug-port-start-only");
        int port = DriverConstruction.resolveDebugPort(config);
        ChromeOptions options = new ChromeOptions();
        DriverConstruction.applyDebugPort(options, "chrome", config);
        DriverConstruction.applyDebugPort(new ChromeOptions(), "chrome", config);
        assertTrue(argumentText(options).contains("--remote-debugging-port=" + port));
        assertFalse(DebugPortOwner.ports().stream().anyMatch(line -> line.startsWith(port + " ")),
                DebugPortOwner.ports().toString());
    }

    private static ObjectNode config(String browser) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("browser", browser);
        return node;
    }

    private static String debuggerAddress(ChromeOptions options) {
        Object raw = options.getCapability("goog:chromeOptions");
        if (!(raw instanceof Map<?, ?> map)) return null;
        Object address = map.get("debuggerAddress");
        return address == null ? null : address.toString();
    }

    private static String argumentText(ChromeOptions options) {
        Object raw = options.getCapability("goog:chromeOptions");
        if (!(raw instanceof Map<?, ?> map)) return "";
        Object args = map.get("args");
        if (!(args instanceof List<?> list)) return "";
        StringBuilder text = new StringBuilder();
        for (Object arg : list) {
            if (arg != null) text.append(arg).append(' ');
        }
        return text.toString();
    }

    private static WebDriver stubDriver() {
        return (WebDriver) Proxy.newProxyInstance(WebDriver.class.getClassLoader(), new Class<?>[]{WebDriver.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "stub-driver";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && args.length == 1 && proxy == args[0];
                    default -> switch (method.getReturnType().getName()) {
                        case "boolean" -> false;
                        case "int" -> 0;
                        case "long" -> 0L;
                        default -> null;
                    };
                });
    }
}
