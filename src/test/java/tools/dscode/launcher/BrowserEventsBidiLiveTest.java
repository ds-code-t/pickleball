package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.common.driver.DriverConstruction;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2 live over WebDriver BiDi: a Selenium Chrome session (BiDi on by default) feeds the same event stream with
 * source=bidi. The same assertions run on Firefox when a Firefox binary is present (none here: visible skip).
 */
class BrowserEventsBidiLiveTest {
    @TempDir
    static Path temp;
    static ChromeDriver driver;
    static HttpServer server;
    static String base;
    static String bidi;
    static String cdp;

    @BeforeAll
    static void start() throws Exception {
        String binary = BrowserSession.findChrome();
        Assumptions.assumeTrue(binary != null, "skipped: Chrome is not present");
        ObjectNode empty = JsonNodeFactory.instance.objectNode();
        ChromeOptions options = DriverConstruction.buildChromeOptions(empty, empty, false);
        options.setBinary(binary);
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu");
        driver = new ChromeDriver(options);
        bidi = String.valueOf(driver.getCapabilities().getCapability("webSocketUrl"));
        @SuppressWarnings("unchecked")
        Map<String, Object> goog = (Map<String, Object>) driver.getCapabilities().getCapability("goog:chromeOptions");
        cdp = "http://" + goog.get("debuggerAddress");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = (path.startsWith("/api") ? "bidi api" : BrowserEventsLiveTest.PAGE).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", path.startsWith("/api") ? "text/plain" : "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        PwCommandLiveTest.agentIn(temp.resolve("bidi"), "browser-events", "--stop");
        if (driver != null) driver.quit();
        if (server != null) server.stop(0);
    }

    @Test
    void bidiFeedsNavConsoleAndNetRows() throws Exception {
        Path project = temp.resolve("bidi");
        JsonNode started = BrowserEventsLiveTest.ok(project, "browser-events", "--start", "--cdp-endpoint=" + cdp, "--bidi-endpoint=" + bidi);
        assertEquals("bidi", started.path("via").asText(), started.toString());
        driver.get(base + "/");
        List<JsonNode> rows = BrowserEventsLiveTest.collect(project, started.path("cursor").asText(), Set.of("nav", "console", "net"));
        assertTrue(rows.stream().anyMatch(r -> "console".equals(r.path("kind").asText()) && r.path("text").asText().contains("hello from page")), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> "net".equals(r.path("kind").asText()) && r.path("url").asText().endsWith("/api/data")), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> "nav".equals(r.path("kind").asText())), rows.toString());
        assertTrue(rows.stream().allMatch(r -> "bidi".equals(r.path("source").asText())), rows.toString());
        driver.executeScript("console.error('second from selenium')");
        List<JsonNode> more = BrowserEventsLiveTest.collect(project, Long.toString(rows.get(rows.size() - 1).path("seq").asLong()), Set.of("console"));
        assertTrue(more.stream().anyMatch(r -> r.path("text").asText().equals("second from selenium") && "error".equals(r.path("level").asText())), more.toString());
    }

    @Test
    void firefoxBidiWhenPresent() {
        boolean present = ConsumerReadyCommands.onPath("firefox") || System.getenv("FIREFOX_BIN") != null;
        Assumptions.assumeTrue(present, "skipped: no Firefox binary on this machine");
        org.openqa.selenium.firefox.FirefoxOptions options = new org.openqa.selenium.firefox.FirefoxOptions();
        options.setCapability(DriverConstruction.WEB_SOCKET_URL, true);
        options.addArguments("-headless");
        org.openqa.selenium.firefox.FirefoxDriver fx = new org.openqa.selenium.firefox.FirefoxDriver(options);
        Path project = temp.resolve("firefox");
        try {
            String ws = String.valueOf(fx.getCapabilities().getCapability("webSocketUrl"));
            JsonNode started = BrowserEventsLiveTest.ok(project, "browser-events", "--start", "--bidi-endpoint=" + ws);
            assertEquals("bidi", started.path("via").asText(), started.toString());
            fx.get(base + "/");
            List<JsonNode> rows = BrowserEventsLiveTest.collect(project, started.path("cursor").asText(), Set.of("nav", "console", "net"));
            assertTrue(rows.stream().anyMatch(r -> r.path("text").asText().contains("hello from page")), rows.toString());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            PwCommandLiveTest.agentIn(project, "browser-events", "--stop");
            fx.quit();
        }
    }
}
