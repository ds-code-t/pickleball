package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.cdp.CdpConnection;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LiveChromeSnapshotTest {
    @AfterEach
    void clean() {
        SessionRegistry.resetForTests();
    }

    @Test
    void liveChromeSnapshotHasScreenshotSourceBoxesStatesFramesAndPremap() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: Chrome is not present");
        byte[] page = """
                <!DOCTYPE html><html><body style="height:2400px">
                <h1 id="marker">pickleball-live-snapshot</h1>
                <input id="locked" type="checkbox" disabled>
                <div style="height:500px"></div>
                <button id="below">Below</button>
                <iframe id="inner" src="/frame.html"></iframe>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8);
        byte[] frame = """
                <!DOCTYPE html><html><body><p id="inside">inside-frame</p></body></html>
                """.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestURI().getPath().endsWith("frame.html") ? frame : page;
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        Path profile = ChromeProcess.newProfile();
        ChromeProcess chrome = ChromeProcess.start(BrowserSession.findChrome(), profile, true, List.of(), url);
        Path dir = Files.createTempDirectory("domengine-live-snap");
        try {
            assertTrue(chrome.debuggerOpen(), "Chrome debugger did not open");
            BrowserSession.awaitInitialDocument(chrome.http(), url);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> version = client.send(
                    HttpRequest.newBuilder(URI.create(chrome.http() + "/json/version")).GET().timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            String browserSocket = Jsons.MAPPER.readTree(version.body()).path("webSocketDebuggerUrl").asText("");
            assertTrue(browserSocket.contains("/devtools/browser/"), browserSocket);
            dev.domengine.cli.CliResult attached = dev.domengine.cli.DomEngineMain.dispatch(List.of(
                    "session", "attach",
                    "--id", "live-snap",
                    "--cdp", browserSocket,
                    "--i-accept-local-attach",
                    "--session-dir", dir.toString()
            ));
            assertEquals(0, attached.exit(), attached.stderr());
            BrowserSession session = SessionRegistry.get().require("live-snap");
            CdpConnection connection = session.connection();
            ObjectNodeEval(connection, "window.scrollTo(0, 220)");
            Path out = dir.resolve("out");
            dev.domengine.cli.CliResult snap = dev.domengine.cli.DomEngineMain.dispatch(List.of(
                    "snapshot", "--session", "live-snap", "--out-dir", out.toString(), "--session-dir", dir.toString()
            ));
            assertEquals(0, snap.exit(), snap.stderr());
            JsonNode json = Jsons.MAPPER.readTree(snap.stdout());
            SnapshotSchemas.assertValid(schema(), json);
            assertEquals("cdp", json.path("transport").asText());
            assertTrue(json.path("platform").asText().equals("chrome") || json.path("platform").asText().equals("chromium"),
                    json.path("platform").asText());
            assertTrue(json.path("screenshot").path("width_px").asInt() > 0);
            assertTrue(Files.exists(out.resolve("screenshot.png")));
            assertTrue(json.path("source").path("text").asText().contains("pickleball-live-snapshot"), json.path("source").path("text").asText());
            assertTrue(json.path("doc_version").asText().contains(":"), json.path("doc_version").asText());
            assertTrue(json.path("premap").path("sections").size() > 0);
            JsonNode button = byId(json, "below");
            assertNotEquals(
                    button.path("boxes").path("document_css_px").path("y").asDouble(),
                    button.path("boxes").path("layout_viewport").path("y").asDouble(),
                    button.path("boxes").toString());
            assertTrue(button.path("boxes").path("frame_document").isObject());
            assertTrue(button.path("boxes").path("visual_viewport").isObject());
            JsonNode locked = byId(json, "locked");
            assertTrue(locked.path("states").toString().contains("disabled"), locked.path("states").toString());
            JsonNode inside = byId(json, "inside");
            assertFalse(inside.path("frame_path").asText().isBlank(), inside.toString());
            assertFalse(inside.path("xpath").asText().isBlank(), inside.toString());
            String resolved = ObjectNodeEval(connection,
                    "var r=document.evaluate(" + Jsons.MAPPER.writeValueAsString(button.path("xpath").asText())
                            + ", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue; r ? r.id : ''");
            assertEquals("below", resolved);
        } finally {
            server.stop(0);
            chrome.destroyTree();
            ChromeProcess.deleteProfile(profile);
        }
    }

    private static JsonNode schema() throws Exception {
        return Jsons.MAPPER.readTree(CompositeSnapshot.class.getResourceAsStream(
                "/dev/domengine/snapshot/composite-snapshot.schema.json"));
    }

    private static JsonNode byId(JsonNode json, String id) {
        for (JsonNode node : json.path("nodes")) {
            if (id.equals(node.path("attributes").path("id").asText())) return node;
        }
        throw new AssertionError("missing " + id);
    }

    private static String ObjectNodeEval(CdpConnection connection, String expression) {
        com.fasterxml.jackson.databind.node.ObjectNode params = Jsons.object();
        params.put("expression", expression);
        params.put("returnByValue", true);
        JsonNode value = connection.call("Runtime.evaluate", params, Duration.ofSeconds(8));
        return value.path("result").path("value").asText("");
    }
}
