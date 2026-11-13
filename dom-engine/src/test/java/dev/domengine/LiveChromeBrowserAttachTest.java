package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.session.BrowserSession;
import dev.domengine.session.ChromeProcess;
import dev.domengine.session.SessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A browser-level DevTools URL must still query the page. {@code DOM.*} is not
 * on the browser target.
 */
class LiveChromeBrowserAttachTest {
    @AfterEach
    void clean() {
        SessionRegistry.resetForTests();
    }

    @Test
    void browserSocketAttachThenQueryHitsThePage() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: Chrome is not present");
        byte[] page = """
                <!DOCTYPE html><html><body><h1 id="marker">pickleball-page-target</h1></body></html>
                """.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        Path profile = ChromeProcess.newProfile();
        ChromeProcess chrome = ChromeProcess.start(BrowserSession.findChrome(), profile, true, List.of(), url);
        Path dir = Files.createTempDirectory("domengine-browser-attach");
        try {
            assertTrue(chrome.debuggerOpen(), "Chrome debugger did not open");
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> version = client.send(
                    HttpRequest.newBuilder(URI.create(chrome.http() + "/json/version")).GET().timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode versionJson = Jsons.MAPPER.readTree(version.body());
            String browserSocket = versionJson.path("webSocketDebuggerUrl").asText("");
            assertTrue(browserSocket.contains("/devtools/browser/"), browserSocket);
            CliResult attached = DomEngineMain.dispatch(List.of(
                    "session", "attach",
                    "--id", "browser-page",
                    "--cdp", browserSocket,
                    "--i-accept-local-attach",
                    "--session-dir", dir.toString()
            ));
            assertEquals(0, attached.exit(), attached.stderr());
            assertFalse(attached.stdout().contains("wasn't found"), attached.stdout());
            CliResult query = DomEngineMain.dispatch(List.of(
                    "query", "--session", "browser-page", "--css", "#marker", "--session-dir", dir.toString()
            ));
            assertEquals(0, query.exit(), query.stderr());
            assertTrue(query.stdout().contains("pickleball-page-target"), query.stdout());
            assertFalse(query.stderr().contains("DOM.getDocument"), query.stderr());
        } finally {
            server.stop(0);
            chrome.destroyTree();
            ChromeProcess.deleteProfile(profile);
        }
    }
}
