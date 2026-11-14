package tools.dscode.common.seleniumextensions;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.json.Jsons;
import dev.domengine.snapshot.CompositeSnapshot;
import dev.domengine.snapshot.SnapshotOptions;
import dev.domengine.snapshot.SnapshotSchemas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LiveFirefoxSnapshotTest {
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void liveFirefoxSnapshotHasScreenshotSourceBoxesAndPremap() throws Exception {
        Path firefox = binary("firefox");
        Path gecko = binary("geckodriver");
        assumeTrue(firefox != null, "skipped: firefox is not on PATH");
        assumeTrue(gecko != null, "skipped: geckodriver is not on PATH");
        byte[] page = """
                <!DOCTYPE html><html><body style="height:1800px">
                <h1 id="marker">pickleball-live-firefox</h1>
                <button id="go" disabled>Go</button>
                <iframe id="inner" src="/frame.html"></iframe>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8);
        byte[] frame = "<!DOCTYPE html><html><body><p id=\"inside\">inside-frame</p></body></html>"
                .getBytes(StandardCharsets.UTF_8);
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
        FirefoxOptions options = new FirefoxOptions();
        options.setBinary(firefox.toString());
        options.addArguments("-headless");
        options.setCapability("webSocketUrl", true);
        WebDriver driver = new FirefoxDriver(options);
        Path out = Files.createTempDirectory("firefox-snap");
        try {
            driver.get(url);
            ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("window.scrollTo(0, 140)");
            JsonNode json = SnapshotProducers.page(driver, SnapshotOptions.fromFlags(Map.of("out-dir", out.toString())));
            JsonNode schema = Jsons.MAPPER.readTree(CompositeSnapshot.class.getResourceAsStream(
                    "/dev/domengine/snapshot/composite-snapshot.schema.json"));
            SnapshotSchemas.assertValid(schema, json);
            assertEquals("firefox", json.path("platform").asText());
            assertTrue(json.path("transport").asText().equals("bidi") || json.path("transport").asText().equals("selenium-fallback"),
                    json.path("transport").asText());
            assertTrue(Files.exists(out.resolve("screenshot.png")));
            assertTrue(json.path("screenshot").path("width_px").asInt() > 0);
            String source = json.path("source").path("text").asText();
            assertTrue(source.contains("pickleball-live-firefox"), source.length() > 240 ? source.substring(0, 240) : source);
            assertTrue(json.path("doc_version").asText().contains(":"), json.path("doc_version").asText());
            assertTrue(json.path("premap").has("indexes"));
            JsonNode button = byId(json, "go");
            assertFalse(button.path("boxes").path("document_css_px").isNull(), button.toString());
            assertFalse(button.path("xpath").asText().isBlank());
            assertTrue(button.path("states").toString().contains("disabled")
                    || button.path("attributes").has("disabled"), button.toString());
        } finally {
            server.stop(0);
            driver.quit();
        }
    }

    private static JsonNode byId(JsonNode json, String id) {
        for (JsonNode node : json.path("nodes")) {
            if (id.equals(node.path("attributes").path("id").asText())) return node;
        }
        throw new AssertionError("missing " + id);
    }

    private static Path binary(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(":")) {
            Path candidate = Path.of(dir, name);
            if (Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }
}
