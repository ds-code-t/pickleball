package tools.dscode.browser;

import com.sun.net.httpserver.HttpServer;
import io.cucumber.core.runner.CurrentScenarioState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.Response;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.driver.DriverConstruction;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.engine.context.ExecutionContext;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

/**
 * A driver built by {@link DriverConstruction#ready} releases its debug port when that driver is quit.
 * No browser is downloaded.
 */
class DebugPortQuitTest {
    @AfterEach
    void reset() {
        CurrentScenarioState.globalDebugBrowser = false;
        CurrentScenarioState.currentScenarioState.remove();
        DebugPortOwner.resetForTests();
        MappingProcessor.resetCommonMaps();
        CompositeRuntime.resetForTests();
        while (ExecutionContext.current() != null) {
            ExecutionContext.current().close();
        }
    }

    @Test
    void quitOnTheConstructedDriverReleasesThePort() throws Exception {
        reset();
        var config = MAPPER.createObjectNode();
        config.put("browser", "debug-port-quit-r8");
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
            CurrentScenarioState.globalDebugBrowser = true;
            DriverConstruction.applyDebugPort(new ChromeOptions(), "chrome", config);
            assertTrue(DebugPortOwner.ports().contains(port + " driver"), DebugPortOwner.ports().toString());
            RemoteWebDriver built = DriverConstruction.ready(remote());
            built.quit();
            assertFalse(DebugPortOwner.ports().contains(port + " driver"), DebugPortOwner.ports().toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void closeKeepsTheClaimAndQuitReleasesIt() throws Exception {
        reset();
        var config = MAPPER.createObjectNode();
        config.put("browser", "debug-port-close-r8");
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
            CurrentScenarioState.globalDebugBrowser = true;
            DriverConstruction.applyDebugPort(new ChromeOptions(), "chrome", config);
            assertTrue(DebugPortOwner.ports().contains(port + " driver"), DebugPortOwner.ports().toString());
            RemoteWebDriver built = DriverConstruction.ready(closeRemote());
            built.close();
            assertTrue(DebugPortOwner.ports().contains(port + " driver"), DebugPortOwner.ports().toString());
            built.quit();
            assertFalse(DebugPortOwner.ports().contains(port + " driver"), DebugPortOwner.ports().toString());
        } finally {
            server.stop(0);
        }
    }

    /** Selenium 4.44 casts the close response to {@code ArrayList} of window handles. */
    private static RemoteWebDriver closeRemote() {
        MutableCapabilities caps = new MutableCapabilities();
        caps.setCapability("browserName", "chrome");
        return new RemoteWebDriver(command -> {
            Response response = new Response();
            response.setState("success");
            response.setSessionId("close-keeps");
            if (command != null && "close".equals(command.getName())) {
                response.setValue(new ArrayList<>(List.of("window-1")));
                return response;
            }
            Map<String, Object> returned = new LinkedHashMap<>();
            returned.put("browserName", "chrome");
            response.setValue(returned);
            return response;
        }, caps);
    }

    private static RemoteWebDriver remote() {
        MutableCapabilities caps = new MutableCapabilities();
        caps.setCapability("browserName", "chrome");
        return new RemoteWebDriver(command -> {
            Response response = new Response();
            response.setState("success");
            response.setSessionId("quit-release");
            Map<String, Object> returned = new LinkedHashMap<>();
            returned.put("browserName", "chrome");
            response.setValue(returned);
            return response;
        }, caps);
    }
}
