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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Action smoke against a real Chrome DevTools endpoint.
 * The fake-CDP {@link ActionSmokeTest} stays. This test runs only when a Chrome binary is present.
 */
class LiveChromeActionSmokeTest {
    @AfterEach
    void clean() {
        SessionRegistry.resetForTests();
    }

    @Test
    void clickTypeAndScrollOnLiveChrome() throws Exception {
        String chrome = BrowserSession.findChrome();
        assumeTrue(chrome != null,
                "missing Chrome binary (looked for DOMENGINE_CHROME, google-chrome, google-chrome-stable, chromium, chromium-browser, chrome)");
        byte[] page = """
                <!DOCTYPE html>
                <html><body>
                <button id="go">Go</button>
                <input id="box" value="">
                <div id="status">idle</div>
                <script>
                  document.getElementById('go').addEventListener('click', function () {
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
        Path dir = Files.createTempDirectory("domengine-live-chrome");
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try {
            CliResult created = DomEngineMain.dispatch(List.of(
                    "session", "create", "--id", "live-chrome", "--url", url, "--session-dir", dir.toString()
            ));
            assertEquals(0, created.exit(), created.stderr());

            assertOk(DomEngineMain.dispatch(List.of("click", "--session", "live-chrome", "--css", "#go")));
            assertOk(DomEngineMain.dispatch(List.of("type", "--session", "live-chrome", "--css", "#box", "--text", "hi")));
            assertOk(DomEngineMain.dispatch(List.of("scroll", "--session", "live-chrome", "--css", "#go", "--dx", "0", "--dy", "40")));

            CliResult status = DomEngineMain.dispatch(List.of(
                    "eval", "--session", "live-chrome", "--css", "#status",
                    "--source", "function(){return this.textContent}"
            ));
            assertOk(status);
            assertEquals("clicked", Jsons.MAPPER.readTree(status.stdout()).path("value").asText());

            CliResult typed = DomEngineMain.dispatch(List.of(
                    "eval", "--session", "live-chrome", "--css", "#box",
                    "--source", "function(){return this.value}"
            ));
            assertOk(typed);
            JsonNode value = Jsons.MAPPER.readTree(typed.stdout()).path("value");
            assertTrue(value.asText("").contains("hi"), typed.stdout());

            CliResult closed = DomEngineMain.dispatch(List.of("session", "close", "--id", "live-chrome", "--session-dir", dir.toString()));
            assertEquals(0, closed.exit(), closed.stderr());
            assertTrue(Jsons.MAPPER.readTree(closed.stdout()).path("killed").asBoolean());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void zeroDeltaScrollBringsTheTargetIntoView() throws Exception {
        String chrome = BrowserSession.findChrome();
        assumeTrue(chrome != null, "skipped: Chrome is not present");
        byte[] page = """
                <!DOCTYPE html><html><body>
                <p id="scroll-state">Scroll State: top</p>
                <div id="spacer" style="height:1800px"></div>
                <button id="scroll-target" type="button">Scroll Target</button>
                <script>
                  window.addEventListener('scroll', function () {
                    if (window.scrollY > 40) document.getElementById('scroll-state').textContent = 'Scroll State: moved';
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
        Path dir = Files.createTempDirectory("domengine-live-scroll");
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try {
            CliResult created = DomEngineMain.dispatch(List.of(
                    "session", "create", "--id", "live-scroll", "--url", url, "--session-dir", dir.toString()));
            assertEquals(0, created.exit(), created.stderr());
            assertOk(DomEngineMain.dispatch(List.of(
                    "scroll", "--session", "live-scroll", "--css", "#scroll-target", "--dx", "0", "--dy", "0")));
            CliResult state = DomEngineMain.dispatch(List.of(
                    "eval", "--session", "live-scroll", "--css", "#scroll-state",
                    "--source", "function(){return this.textContent}"));
            assertOk(state);
            assertEquals("Scroll State: moved", Jsons.MAPPER.readTree(state.stdout()).path("value").asText(), state.stdout());
        } finally {
            server.stop(0);
            DomEngineMain.dispatch(List.of("session", "close", "--id", "live-scroll", "--session-dir", dir.toString()));
        }
    }

    @Test
    void enterKeyUsesTheDomName() throws Exception {
        String chrome = BrowserSession.findChrome();
        assumeTrue(chrome != null, "skipped: Chrome is not present");
        byte[] page = """
                <!DOCTYPE html><html><body>
                <input id="box">
                <p id="last-key">Last Key: none</p>
                <script>
                  document.getElementById('box').addEventListener('keydown', function (event) {
                    document.getElementById('last-key').textContent = 'Last Key: ' + event.key;
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
        Path dir = Files.createTempDirectory("domengine-live-key");
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try {
            CliResult created = DomEngineMain.dispatch(List.of(
                    "session", "create", "--id", "live-key", "--url", url, "--session-dir", dir.toString()));
            assertEquals(0, created.exit(), created.stderr());
            assertOk(DomEngineMain.dispatch(List.of("click", "--session", "live-key", "--css", "#box")));
            assertOk(DomEngineMain.dispatch(List.of("key", "--session", "live-key", "--css", "#box", "--key", "ENTER")));
            CliResult state = DomEngineMain.dispatch(List.of(
                    "eval", "--session", "live-key", "--css", "#last-key",
                    "--source", "function(){return this.textContent}"));
            assertOk(state);
            assertEquals("Last Key: Enter", Jsons.MAPPER.readTree(state.stdout()).path("value").asText(), state.stdout());
        } finally {
            server.stop(0);
            DomEngineMain.dispatch(List.of("session", "close", "--id", "live-key", "--session-dir", dir.toString()));
        }
    }

    @Test
    void dragJsonLocatorAfterScrollDrops() throws Exception {
        String chrome = BrowserSession.findChrome();
        assumeTrue(chrome != null, "skipped: Chrome is not present");
        byte[] page = """
                <!DOCTYPE html><html><body>
                <button id="drag-source" type="button">Drag Source</button>
                <button id="drop-target" type="button">Drop Target</button>
                <p id="drag-result">Drag Result: none</p>
                <div style="height:1800px"></div>
                <button id="scroll-target" type="button">Scroll Target</button>
                <script>
                  var dragging = false;
                  var source = document.getElementById('drag-source');
                  var target = document.getElementById('drop-target');
                  source.addEventListener('mousedown', function () { dragging = true; });
                  target.addEventListener('mouseup', function () {
                    if (dragging) {
                      dragging = false;
                      document.getElementById('drag-result').textContent = 'Drag Result: dropped';
                    }
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
        Path dir = Files.createTempDirectory("domengine-live-drag");
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try {
            CliResult created = DomEngineMain.dispatch(List.of(
                    "session", "create", "--id", "live-drag", "--url", url, "--session-dir", dir.toString()));
            assertEquals(0, created.exit(), created.stderr());
            assertOk(DomEngineMain.dispatch(List.of(
                    "scroll", "--session", "live-drag", "--css", "#scroll-target", "--dx", "0", "--dy", "0")));
            assertOk(DomEngineMain.dispatch(List.of(
                    "drag", "--session", "live-drag",
                    "--source", "{\"css\":\"#drag-source\"}",
                    "--target", "{\"css\":\"#drop-target\"}")));
            CliResult state = DomEngineMain.dispatch(List.of(
                    "eval", "--session", "live-drag", "--css", "#drag-result",
                    "--source", "function(){return this.textContent}"));
            assertOk(state);
            assertEquals("Drag Result: dropped", Jsons.MAPPER.readTree(state.stdout()).path("value").asText(), state.stdout());
        } finally {
            server.stop(0);
            DomEngineMain.dispatch(List.of("session", "close", "--id", "live-drag", "--session-dir", dir.toString()));
        }
    }

    private static void assertOk(CliResult result) {
        assertEquals(0, result.exit(), result.stderr());
    }
}
