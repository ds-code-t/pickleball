package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Live XPath 1.0 through the page's document.evaluate. */
class LiveChromeXPathTest {
    @AfterEach
    void clean() {
        SessionRegistry.resetForTests();
    }

    @Test
    void documentEvaluateCoversTheXpath10Set() throws Exception {
        String chrome = BrowserSession.findChrome();
        assumeTrue(chrome != null, "skipped: Chrome is not present");
        byte[] page = """
                <!DOCTYPE html><html><body>
                <button id="b1">One</button>
                <button id="b2" class="b">Two</button>
                <span id="vis" class="b">Visible</span>
                <ul>
                  <li>one</li>
                  <li> two </li>
                  <li><a id="inner" href="#x">link</a></li>
                </ul>
                <div id="host"><a id="child" href="#y">child</a></div>
                <p>Only text</p>
                <div id="status">idle</div>
                <script>
                  document.querySelector('p').addEventListener('click', function () {
                    document.getElementById('status').textContent = 'clicked';
                  });
                </script>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        server.start();
        Path dir = Files.createTempDirectory("domengine-live-xpath");
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try {
            CliResult created = DomEngineMain.dispatch(List.of(
                    "session", "create", "--id", "live-xpath", "--url", url, "--session-dir", dir.toString()));
            assertEquals(0, created.exit(), created.stderr());

            assertIds("//*[self::button or @role='button']", "b1", "b2");
            assertIds("//*[@class='b' and @id='vis']", "vis");
            assertIds("//*[normalize-space(.)='Visible']", "vis");
            assertIds("(//button)[2]", "b2");
            assertIds("//li/a", "inner");
            assertIds("//*[@id='child']/ancestor-or-self::div", "host");
            assertIds("//button[translate(@id,'B','b')='b2']", "b2");
            assertIds("//*[contains(concat(' ', normalize-space(@class), ' '), ' b ')]", "b2", "vis");
            JsonNode two = query("//li[normalize-space()='two']");
            assertEquals(1, two.path("nodes").size());
            assertEquals("LI", two.path("nodes").get(0).path("node_name").asText());

            JsonNode host = query("//*[@id='host']");
            String handle = host.path("nodes").get(0).path("handle").asText();
            JsonNode relative = query(".//a", handle);
            assertEquals(List.of("child"), ids(relative));

            CliResult broken = DomEngineMain.dispatch(List.of(
                    "query", "--session", "live-xpath", "--xpath", "//*["));
            assertEquals(1, broken.exit(), broken.stderr());
            assertEquals("invalid", Jsons.MAPPER.readTree(broken.stderr()).path("code").asText(), broken.stderr());

            assertOk(DomEngineMain.dispatch(List.of(
                    "click", "--session", "live-xpath", "--xpath", "//*[normalize-space(.)='Only text']")));
            CliResult status = DomEngineMain.dispatch(List.of(
                    "eval", "--session", "live-xpath", "--css", "#status",
                    "--source", "function(){return this.textContent}"));
            assertOk(status);
            assertEquals("clicked", Jsons.MAPPER.readTree(status.stdout()).path("value").asText());
        } finally {
            server.stop(0);
            DomEngineMain.dispatch(List.of("session", "close", "--id", "live-xpath", "--session-dir", dir.toString()));
        }
    }

    private static void assertIds(String xpath, String... expected) throws Exception {
        assertEquals(List.of(expected), ids(query(xpath)), xpath);
    }

    private static JsonNode query(String xpath) throws Exception {
        return query(xpath, null);
    }

    private static JsonNode query(String xpath, String within) throws Exception {
        List<String> args = new ArrayList<>(List.of("query", "--session", "live-xpath", "--xpath", xpath));
        if (within != null) {
            args.add("--within");
            args.add(within);
        }
        CliResult result = DomEngineMain.dispatch(args);
        assertEquals(0, result.exit(), xpath + " " + result.stderr());
        return Jsons.MAPPER.readTree(result.stdout());
    }

    private static List<String> ids(JsonNode found) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : found.path("nodes")) ids.add(node.path("attributes").path("id").asText(""));
        return ids;
    }

    private static void assertOk(CliResult result) {
        assertEquals(0, result.exit(), result.stderr());
    }
}
