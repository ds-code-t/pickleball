package tools.dscode.common.driver;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.cucumber.core.runner.CurrentScenarioState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.engine.context.ExecutionContext;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

/**
 * {@code AgentControl} {@code launch --start} calls {@code createLocalDriver},
 * which calls {@code applyDebugPort} with no scenario state. That seam is this test.
 */
class ApplyDebugPortTest {
    @AfterEach
    void reset() {
        CurrentScenarioState.globalDebugBrowser = false;
        CurrentScenarioState.currentScenarioState.remove();
        while (ExecutionContext.current() != null) {
            ExecutionContext.current().close();
        }
    }

    @Test
    void noScenarioStateAndUnsetFlagDoesNotAttach() {
        reset();
        ChromeOptions options = new ChromeOptions();
        DriverConstruction.applyDebugPort(options, "chrome", config("chrome"));
        assertNull(debuggerAddress(options));
        assertFalse(argumentText(options).contains("remote-debugging-port"));
    }

    @Test
    void scenarioStateFalseIgnoresTheGlobalFlag() {
        reset();
        MappingProcessor.resetCommonMaps();
        CurrentScenarioState.globalDebugBrowser = true;
        CurrentScenarioState state = new CurrentScenarioState("outside", "feature");
        state.debugBrowser = false;
        CurrentScenarioState.currentScenarioState.set(state);
        ChromeOptions options = new ChromeOptions();
        DriverConstruction.applyDebugPort(options, "chrome", config("chrome"));
        assertNull(debuggerAddress(options));
        assertFalse(argumentText(options).contains("remote-debugging-port"));
    }

    @Test
    void globalFlagWithAListeningPortSetsDebuggerAddress() throws Exception {
        reset();
        CurrentScenarioState.globalDebugBrowser = true;
        ObjectNode config = config("apply-debug-port");
        int port = DriverConstruction.resolveDebugPort(config);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/json/version", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ChromeOptions options = new ChromeOptions();
            DriverConstruction.applyDebugPort(options, "chrome", config);
            assertEquals("127.0.0.1:" + port, debuggerAddress(options));
            assertFalse(argumentText(options).contains("remote-debugging-port"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void globalFlagWithNoListenerStartsADebugPort() {
        reset();
        CurrentScenarioState.globalDebugBrowser = true;
        ObjectNode config = config("apply-debug-port-closed");
        int port = DriverConstruction.resolveDebugPort(config);
        ChromeOptions options = new ChromeOptions();
        DriverConstruction.applyDebugPort(options, "chrome", config);
        assertNull(debuggerAddress(options));
        assertTrue(argumentText(options).contains("--remote-debugging-port=" + port));
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
}
