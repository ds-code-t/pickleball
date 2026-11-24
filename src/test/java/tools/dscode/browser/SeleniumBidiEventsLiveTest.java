package tools.dscode.browser;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.browser.proto.BrowserEventHub;
import tools.dscode.common.driver.DriverConstruction;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E11 live: a Selenium scenario session's console and network rows come through Selenium's own BiDi modules. */
class SeleniumBidiEventsLiveTest {
    @TempDir
    static Path temp;
    static ChromeDriver driver;
    static HttpServer server;
    static String base;

    @BeforeAll
    static void start() throws Exception {
        String binary = BrowserSession.findChrome();
        Assumptions.assumeTrue(binary != null, "skipped: Chrome is not present");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = "/page".equals(path)
                    ? "<!doctype html><title>s</title><script>console.log('hello from selenium page');fetch('/data?token=abc123')</script>"
                    : "{\"ok\":true}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "/page".equals(path) ? "text/html" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        ObjectNode empty = JsonNodeFactory.instance.objectNode();
        ChromeOptions options = DriverConstruction.buildChromeOptions(empty, empty, false);
        options.setBinary(binary);
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu");
        driver = new ChromeDriver(options);
    }

    @AfterAll
    static void stop() {
        if (driver != null) driver.quit();
        if (server != null) server.stop(0);
    }

    @AfterEach
    void clear() {
        System.clearProperty("pkb_scenario_events");
        SeleniumBidiEvents.stop(driver);
    }

    static ObjectNode wait(BrowserEventHub hub, Predicate<ObjectNode> want) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        List<ObjectNode> rows = List.of();
        while (System.currentTimeMillis() < deadline) {
            rows = hub.read("0", null, null, null, 500, 200);
            for (ObjectNode row : rows) if (want.test(row)) return row;
        }
        throw new AssertionError("no matching row in " + rows);
    }

    @Test
    void consoleAndNetworkRowsArriveThroughSeleniumsBidiModules() throws Exception {
        BrowserEventHub hub = new BrowserEventHub(temp.resolve("project"), "scenario-test", "live");
        assertNotNull(SeleniumBidiEvents.watch(driver, hub), "the ChromeDriver session has BiDi");
        driver.get(base + "/page");
        ObjectNode console = wait(hub, r -> "console".equals(r.path("kind").asText()) && r.path("text").asText().contains("hello from selenium page"));
        assertEquals("selenium", console.path("transport").asText(), console.toString());
        assertEquals("bidi", console.path("source").asText());
        ObjectNode response = wait(hub, r -> "net".equals(r.path("kind").asText()) && "response".equals(r.path("subkind").asText())
                && r.path("url").asText().contains("/data"));
        assertEquals(200, response.path("status").asInt(), response.toString());
        assertFalse(response.toString().contains("abc123"), "query secrets are redacted: " + response);
        Path spill = hub.spillFile();
        assertTrue(Files.readString(spill).contains("hello from selenium page"), "rows spill like any session");
    }

    @Test
    void scenarioWatchIsOptIn() {
        SeleniumBidiEvents.watch(driver);
        assertNull(SeleniumBidiEvents.of(driver), "off by default");
        System.setProperty("pkb_scenario_events", "on");
        assertTrue(SeleniumBidiEvents.enabled());
        SeleniumBidiEvents.watch(driver);
        assertNotNull(SeleniumBidiEvents.of(driver));
    }
}
