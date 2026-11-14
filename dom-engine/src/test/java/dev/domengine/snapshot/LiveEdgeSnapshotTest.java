package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LiveEdgeSnapshotTest {
    @AfterEach
    void clean() {
        SessionRegistry.resetForTests();
    }

    @Test
    void edgeTokenInTheVersionStringIsEdge() {
        assertEquals("edge", CdpSnapshotProducer.platformOf("Edg/120.0.2210.91"));
        assertEquals("chromium", CdpSnapshotProducer.platformOf("Chromium/120.0.6099.109"));
        assertEquals("chrome", CdpSnapshotProducer.platformOf("Chrome/120.0.6099.109"));
    }

    @Test
    void liveEdgeSnapshotWhenTheBinaryIsPresent() throws Exception {
        Path edge = edgeBinary();
        assumeTrue(edge != null, "skipped: microsoft-edge is not on PATH");
        byte[] page = """
                <!DOCTYPE html><html><body>
                <h1 id="marker">pickleball-live-edge</h1>
                <button id="go" disabled>Go</button>
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
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        Path profile = ChromeProcess.newProfile();
        ChromeProcess browser = ChromeProcess.start(edge.toString(), profile, true, List.of(), url);
        Path dir = Files.createTempDirectory("domengine-edge-snap");
        try {
            assertTrue(browser.debuggerOpen(), "Edge debugger did not open");
            try {
                BrowserSession.awaitInitialDocument(browser.http(), url);
            } catch (dev.domengine.cdp.CdpException failure) {
                String list = "";
                try {
                    HttpResponse<String> body = HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI.create(browser.http() + "/json/list")).GET().timeout(Duration.ofSeconds(5)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    list = body.body();
                } catch (Exception ignored) {
                    list = "list unavailable";
                }
                throw new AssertionError(edge + " " + failure.getMessage() + " list=" + list, failure);
            }
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> version = client.send(
                    HttpRequest.newBuilder(URI.create(browser.http() + "/json/version")).GET().timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            String browserSocket = Jsons.MAPPER.readTree(version.body()).path("webSocketDebuggerUrl").asText("");
            assertTrue(browserSocket.contains("/devtools/browser/"), browserSocket);
            dev.domengine.cli.CliResult attached = dev.domengine.cli.DomEngineMain.dispatch(List.of(
                    "session", "attach",
                    "--id", "live-edge",
                    "--cdp", browserSocket,
                    "--i-accept-local-attach",
                    "--session-dir", dir.toString()
            ));
            assertEquals(0, attached.exit(), attached.stderr());
            Path out = dir.resolve("out");
            dev.domengine.cli.CliResult snap = dev.domengine.cli.DomEngineMain.dispatch(List.of(
                    "snapshot", "--session", "live-edge", "--out-dir", out.toString(), "--session-dir", dir.toString()
            ));
            assertEquals(0, snap.exit(), snap.stderr());
            JsonNode json = Jsons.MAPPER.readTree(snap.stdout());
            JsonNode schema = Jsons.MAPPER.readTree(CompositeSnapshot.class.getResourceAsStream(
                    "/dev/domengine/snapshot/composite-snapshot.schema.json"));
            SnapshotSchemas.assertValid(schema, json);
            assertEquals("edge", json.path("platform").asText(), json.path("platform").asText());
            assertEquals("cdp", json.path("transport").asText());
            assertTrue(json.path("screenshot").path("width_px").asInt() > 0);
            assertTrue(Files.exists(out.resolve("screenshot.png")));
            assertTrue(json.path("source").path("text").asText().contains("pickleball-live-edge"));
            assertTrue(json.path("doc_version").asText().contains(":"), json.path("doc_version").asText());
            assertTrue(json.path("premap").has("indexes"));
            assertTrue(json.path("nodes").size() > 3, snap.stdout());
        } finally {
            server.stop(0);
            browser.destroyTree();
            ChromeProcess.deleteProfile(profile);
        }
    }

    private static Path edgeBinary() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(":")) {
            for (String name : new String[] {"microsoft-edge", "microsoft-edge-stable"}) {
                Path candidate = Path.of(dir, name);
                if (Files.isExecutable(candidate)) return candidate;
            }
        }
        return null;
    }
}
