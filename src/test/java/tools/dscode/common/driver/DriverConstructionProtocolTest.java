package tools.dscode.common.driver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.Response;
import tools.dscode.browser.BrowserFacade;
import tools.dscode.browser.BrowserLog;
import tools.dscode.browser.ProtocolSelector;
import tools.dscode.browser.ProtocolSockets;
import tools.dscode.browser.ScriptedSocket;
import tools.dscode.browser.SeleniumAdapter;
import tools.dscode.common.agentcontrol.AgentDriverHooks;
import tools.dscode.common.composite.CompositeMode;
import tools.dscode.common.composite.CompositeRuntime;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverConstructionProtocolTest {
    @TempDir
    Path temp;

    @BeforeEach
    void logs() {
        ProtocolSelector.resetForTests();
        CompositeRuntime.resetForTests();
        System.clearProperty(CompositeMode.KEY);
        System.setProperty(BrowserLog.DIR_KEY, temp.toString());
        System.setProperty(BrowserLog.FALLBACK_KEY, "warn");
        System.setProperty(BrowserLog.KEY, "selector,fallback");
    }

    @AfterEach
    void clear() {
        System.clearProperty(BrowserLog.DIR_KEY);
        System.clearProperty(BrowserLog.FALLBACK_KEY);
        System.clearProperty(BrowserLog.KEY);
        System.clearProperty(CompositeMode.KEY);
        ProtocolSelector.resetForTests();
        CompositeRuntime.resetForTests();
    }

    @Test
    void readySelectsSeleniumAndDoesNotOpenAPublishedCdpSocket() {
        AtomicInteger opens = new AtomicInteger();
        ProtocolSockets.useFactoryForTests(uri -> {
            opens.incrementAndGet();
            return new ScriptedSocket();
        });
        ProtocolSelector.useEndpointsForTests(new ProtocolSelector.Endpoints() {
            @Override
            public URI cdp(WebDriver driver) {
                return URI.create("ws://127.0.0.1:9/devtools/browser/published");
            }

            @Override
            public URI bidi(WebDriver driver) {
                return URI.create("ws://127.0.0.1:9/session");
            }
        });
        RemoteWebDriver driver = remote("ws://127.0.0.1:9222/devtools/browser/published");
        try {
            DriverConstruction.ready(driver);
            BrowserFacade selected = DriverConstruction.scriptedProtocol(driver);
            assertInstanceOf(SeleniumAdapter.class, selected);
            assertEquals("selenium", selected.protocol());
            assertEquals(0, opens.get());
            assertTrue(BrowserLog.linesForTests().stream().anyMatch(line -> line.contains("library selenium")),
                    BrowserLog.linesForTests().toString());
            assertTrue(BrowserLog.linesForTests().stream().noneMatch(line -> line.contains("WARN")),
                    BrowserLog.linesForTests().toString());
        } finally {
            try {
                AgentDriverHooks.quit();
            } catch (RuntimeException ignored) {
                // The fake session has no tabs. Selection already ran.
            }
        }
    }

    private static RemoteWebDriver remote(String cdp) {
        MutableCapabilities caps = new MutableCapabilities();
        caps.setCapability("browserName", "chrome");
        caps.setCapability("se:cdp", cdp);
        return new RemoteWebDriver(command -> {
            Response response = new Response();
            response.setState("success");
            response.setSessionId("construction-1");
            Map<String, Object> returned = new LinkedHashMap<>();
            returned.put("browserName", "chrome");
            returned.put("se:cdp", cdp);
            response.setValue(returned);
            return response;
        }, caps);
    }
}
