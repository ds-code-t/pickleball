package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.session.BrowserSession;
import dev.domengine.session.SessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Chrome: a navigation after the session opened lands in the observe buffer. Skips only without Chrome. */
class LiveChromeObserveEventsTest {
    @AfterEach
    void clean() {
        SessionRegistry.resetForTests();
    }

    @Test
    void navigationNetworkAndConsoleEventsAreBuffered() throws Exception {
        String chrome = BrowserSession.findChrome();
        assumeTrue(chrome != null,
                "missing Chrome binary (looked for DOMENGINE_CHROME, google-chrome, google-chrome-stable, chromium, chromium-browser, chrome)");
        byte[] page = """
                <!DOCTYPE html>
                <html><body><p id="p">nav</p>
                <script>console.log('observe-hello');</script>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/nav-target", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        server.start();
        Path dir = Files.createTempDirectory("domengine-live-observe");
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/nav-target";
        try {
            CliResult created = DomEngineMain.dispatch(List.of(
                    "session", "create", "--id", "live-observe", "--session-dir", dir.toString()));
            assertEquals(0, created.exit(), created.stderr());
            long before = System.currentTimeMillis() - 1000;
            ObjectNode params = Jsons.object();
            params.put("url", url);
            SessionRegistry.get().require("live-observe").connection()
                    .call("Page.navigate", params, Duration.ofSeconds(15));

            JsonNode nav = observe("--type", "navigation", "--url", "/nav-target", "--wait-ms", "10000");
            assertTrue(nav.path("count").asInt() >= 1, nav.toString());
            JsonNode navRow = nav.path("events").get(0);
            assertEquals("Page.frameNavigated", navRow.path("method").asText());
            assertTrue(navRow.path("top").asBoolean(), navRow.toString());
            assertTrue(navRow.path("at").asLong() >= before, navRow.toString());

            JsonNode response = observe("--type", "Network.responseReceived", "--url", "/nav-target", "--wait-ms", "10000");
            assertTrue(response.path("count").asInt() >= 1, response.toString());
            assertEquals(200, response.path("events").get(0).path("status").asInt(), response.toString());
            assertEquals("text/html", response.path("events").get(0).path("mime").asText(), response.toString());

            JsonNode request = observe("--type", "Network.requestWillBeSent", "--url", "/nav-target", "--wait-ms", "10000");
            assertEquals("GET", request.path("events").get(0).path("httpMethod").asText(), request.toString());

            JsonNode console = observe("--type", "console", "--url", "observe-hello", "--wait-ms", "10000");
            assertTrue(console.path("count").asInt() >= 1, console.toString());
            assertEquals("log", console.path("events").get(0).path("level").asText());

            CliResult closed = DomEngineMain.dispatch(List.of("session", "close", "--id", "live-observe", "--session-dir", dir.toString()));
            assertEquals(0, closed.exit(), closed.stderr());
        } finally {
            server.stop(0);
        }
    }

    private static JsonNode observe(String... filters) throws Exception {
        List<String> argv = new java.util.ArrayList<>(List.of("observe", "--session", "live-observe"));
        argv.addAll(List.of(filters));
        CliResult result = DomEngineMain.dispatch(argv);
        assertEquals(0, result.exit(), result.stderr());
        return Jsons.MAPPER.readTree(result.stdout());
    }
}
