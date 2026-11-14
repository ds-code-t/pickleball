package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.FakeCdp;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.desktop.NativeDesktop;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One composite snapshot per platform: screenshot, source, structure, paths,
 * viewport, and pre-map. Fake or fixture everywhere. Live only when that
 * platform is actually on this host.
 */
class SnapshotCoverageTest {
    private static final String PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @AfterEach
    void clean() {
        DesktopTree.resetForTests();
        SessionRegistry.resetForTests();
    }

    @Test
    void everyPlatformEmitsTheCombinedArtifact() throws Exception {
        assertBrowser("Chrome/120.0.6099.109", "chrome");
        assertBrowser("Chromium/120.0.6099.109", "chromium");
        assertBrowser("Edg/120.0.2210.91", "edge");
        assertFirefox();
        assertDesktop("windows", "windows-uia");
        assertDesktop("macos", "macos-ax");
        assertDesktop("linux", "linux-atspi");
    }

    @Test
    void liveChromeWhenPresent() throws Exception {
        String chrome = BrowserSession.findChrome();
        assumeTrue(chrome != null, "skipped: Chrome is not present");
        liveCdp(chrome, "chrome", "chromium", "pickleball-coverage-chrome");
    }

    @Test
    void liveEdgeWhenPresent() throws Exception {
        Path edge = edgeBinary();
        assumeTrue(edge != null, "skipped: microsoft-edge is not on PATH");
        liveCdp(edge.toString(), "edge", "edge", "pickleball-coverage-edge");
    }

    @Test
    void liveDesktopWhenThePlatformExists() throws Exception {
        String family = NativeDesktop.family();
        if ("linux".equals(family)) {
            boolean display = System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
            assumeTrue(display, "no display");
        }
        CliResult attached = DomEngineMain.dispatch(List.of("desktop", "attach", "--id", "cov-live", "--live"));
        if (attached.exit() != 0 && (attached.stderr().contains("unavailable")
                || attached.stderr().contains("permission")
                || attached.stderr().contains("AT-SPI"))) {
            assumeTrue(false, attached.stderr());
        }
        assertEquals(0, attached.exit(), attached.stderr());
        CliResult shot = DomEngineMain.dispatch(List.of("desktop", "snapshot", "--id", "cov-live"));
        if (shot.exit() != 0 && (shot.stderr().contains("permission") || shot.stderr().contains("screenshot failed"))) {
            assumeTrue(false, shot.stderr());
        }
        assertEquals(0, shot.exit(), shot.stderr());
        JsonNode json = Jsons.MAPPER.readTree(shot.stdout());
        String platform = switch (family) {
            case "windows" -> "windows-uia";
            case "macos" -> "macos-ax";
            default -> "linux-atspi";
        };
        assertParts(json, platform, json.path("transport").asText());
        assertFalse(json.path("source").path("text").asText().isBlank());
    }

    private static void assertBrowser(String product, String platform) throws Exception {
        try (FakeCdp cdp = FakeCdp.start()) {
            cdp.product = product;
            CdpConnection connection = CdpConnection.connect(
                    URI.create("ws://127.0.0.1:" + cdp.port + "/devtools/page/PAGE1"),
                    Duration.ofSeconds(5));
            try {
                JsonNode json = CdpSnapshotProducer.produce(connection, platform, Map.of());
                assertParts(json, platform, "cdp");
            } finally {
                connection.close();
            }
        }
    }

    private static void assertFirefox() throws Exception {
        String html = "<!DOCTYPE html><html><body><button id=\"go\">Go</button></body></html>";
        byte[] png = Base64.getDecoder().decode(PNG);
        SnapshotContext context = SnapshotContext.captured(
                new SnapshotOptions(), "firefox", "bidi", "main:1", html, png,
                800, 600, 1, 0, 0, 0, 0, 1);
        JsonNode json = CompositeSnapshotBuilder.build(HtmlTree.parse(html), context).toJson();
        assertParts(json, "firefox", "bidi");
        String producer = Files.readString(facadeProducer());
        assertTrue(producer.contains("return \"firefox\""), "FacadeSnapshotProducer declares firefox for BiDi");
        assertTrue(producer.contains("CompositeSnapshotBuilder"));
    }

    private static void assertDesktop(String family, String platform) throws Exception {
        Path fixture = Files.createTempFile("cov-" + family, ".json");
        Files.writeString(fixture, """
                {"role":"window","name":"Desk","x":0,"y":0,"width":200,"height":100,"children":[
                  {"role":"button","name":"Save","x":8,"y":8,"width":40,"height":20}
                ]}
                """);
        DesktopTree.Session attached = DesktopTree.attach("cov-" + family, fixture);
        DesktopTree.Session named = new DesktopTree.Session(
                attached.id(), attached.backend(), attached.root(), false, family, "", attached.sequence());
        Path png = Files.createTempFile("cov-shot", ".png");
        Files.write(png, Base64.getDecoder().decode(PNG));
        JsonNode json = DesktopSnapshotProducer.produce(named, Map.of("screenshot", png.toString()));
        assertParts(json, platform, "fixture");
        assertEquals("a11y-xml", json.path("source").path("kind").asText());
    }

    private static void liveCdp(String binary, String platform, String also, String marker) throws Exception {
        byte[] page = ("<!DOCTYPE html><html><body><h1 id=\"marker\">" + marker
                + "</h1><button id=\"go\">Go</button></body></html>").getBytes(StandardCharsets.UTF_8);
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
        Path dir = Files.createTempDirectory("coverage-live");
        ChromeProcess browser = null;
        try {
            browser = ChromeProcess.start(binary, profile, true, List.of(), url);
            assertTrue(browser.debuggerOpen(), "debugger did not open");
            BrowserSession.awaitInitialDocument(browser.http(), url);
            HttpResponse<String> version = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(browser.http() + "/json/version")).GET()
                            .timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            String socket = Jsons.MAPPER.readTree(version.body()).path("webSocketDebuggerUrl").asText("");
            assertTrue(socket.contains("/devtools/browser/"), socket);
            CliResult attached = DomEngineMain.dispatch(List.of(
                    "session", "attach", "--id", "cov-browser", "--cdp", socket,
                    "--i-accept-local-attach", "--session-dir", dir.toString()));
            assertEquals(0, attached.exit(), attached.stderr());
            Path out = dir.resolve("out");
            CliResult snap = DomEngineMain.dispatch(List.of(
                    "snapshot", "--session", "cov-browser", "--out-dir", out.toString(),
                    "--session-dir", dir.toString()));
            assertEquals(0, snap.exit(), snap.stderr());
            JsonNode json = Jsons.MAPPER.readTree(snap.stdout());
            String seen = json.path("platform").asText();
            assertTrue(seen.equals(platform) || seen.equals(also), seen);
            assertParts(json, seen, "cdp");
            assertTrue(json.path("source").path("text").asText().contains(marker));
            assertTrue(Files.exists(out.resolve("screenshot.png")));
        } finally {
            server.stop(0);
            if (browser != null) browser.destroyTree();
            else ChromeProcess.releaseProfile(profile);
            ChromeProcess.deleteProfile(profile);
        }
    }

    private static void assertParts(JsonNode json, String platform, String transport) throws Exception {
        SnapshotSchemas.assertValid(schema(), json);
        assertEquals(platform, json.path("platform").asText(), json.path("platform").asText());
        assertEquals(transport, json.path("transport").asText());
        assertTrue(json.path("screenshot").path("width_px").asInt() > 0, json.path("screenshot").toString());
        assertTrue(json.path("screenshot").path("sha256").asText().length() > 10);
        assertFalse(json.path("source").path("text").asText().isBlank());
        assertTrue(json.path("nodes").size() > 0, json.toString());
        boolean path = false;
        for (JsonNode node : json.path("nodes")) {
            if (!node.path("xpath").asText("").isBlank() || !node.path("path").asText("").isBlank()) path = true;
        }
        assertTrue(path, "no xpath or path");
        assertTrue(json.path("viewport").isObject());
        assertTrue(json.path("viewport").has("css_width"));
        assertTrue(json.path("premap").has("indexes"));
        assertTrue(json.path("premap").has("viewport_map"));
    }

    private static JsonNode schema() throws Exception {
        return Jsons.MAPPER.readTree(CompositeSnapshot.class.getResourceAsStream(
                "/dev/domengine/snapshot/composite-snapshot.schema.json"));
    }

    private static Path facadeProducer() {
        Path direct = Path.of("src/main/java/tools/dscode/common/seleniumextensions/FacadeSnapshotProducer.java");
        if (Files.exists(direct)) return direct;
        return Path.of("..").resolve(direct);
    }

    private static Path edgeBinary() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(":")) {
            if (dir.isBlank()) continue;
            Path candidate = Path.of(dir, "microsoft-edge");
            if (Files.isExecutable(candidate)) return candidate;
        }
        Path packaged = Path.of("/opt/microsoft/msedge/msedge");
        return Files.isExecutable(packaged) ? packaged : null;
    }
}
