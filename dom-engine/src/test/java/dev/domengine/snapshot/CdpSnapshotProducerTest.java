package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.FakeCdp;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.json.Jsons;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdpSnapshotProducerTest {
    private static final List<String> METHODS = List.of(
            "Browser.getVersion",
            "DOM.getDocument",
            "Page.getFrameTree",
            "DOMSnapshot.captureSnapshot",
            "Page.getLayoutMetrics",
            "Accessibility.getFullAXTree",
            "Accessibility.getFullAXTree",
            "Page.captureScreenshot",
            "DOM.getOuterHTML",
            "DOM.getOuterHTML",
            "Page.addScriptToEvaluateOnNewDocument",
            "Page.createIsolatedWorld",
            "Runtime.evaluate"
    );

    @Test
    void oneSnapshotCallFillsDistinctBoxesAndSkipsPerNodeReads() throws Exception {
        try (FakeCdp cdp = FakeCdp.start()) {
            CdpConnection connection = CdpConnection.connect(
                    URI.create("ws://127.0.0.1:" + cdp.port + "/devtools/page/PAGE1"),
                    java.time.Duration.ofSeconds(5));
            try {
                JsonNode json = CdpSnapshotProducer.produce(connection, "s", java.util.Map.of());
                assertEquals(METHODS, cdp.methods);
                for (String banned : List.of(
                        "CSS.getComputedStyleForNode", "DOM.getBoxModel", "Accessibility.getPartialAXTree", "DOM.resolveNode")) {
                    assertFalse(cdp.methods.contains(banned), banned);
                }
                assertEquals("pickleball.composite-snapshot", json.path("schema").asText());
                assertEquals("chrome", json.path("platform").asText());
                assertEquals("cdp", json.path("transport").asText());
                assertEquals("L1:4", json.path("doc_version").asText());
                assertEquals(80, json.path("viewport").path("scroll_y").asDouble(), 1e-9);
                assertTrue(json.path("screenshot").path("sha256").asText().length() > 10);
                assertTrue(json.path("source").path("text").asText().contains("framed"));
                assertTrue(json.path("premap").has("indexes"));
                assertEquals("main", json.path("frames").get(0).path("id").asText());
                assertEquals("child", json.path("frames").get(1).path("id").asText());
                assertTrue(json.path("document").toString().contains("shadow_root"));
                assertTrue(json.path("ok").asBoolean());
                JsonNode button = node(json, "go");
                assertEquals("button", button.path("role").asText());
                assertEquals("Go", button.path("name").asText());
                assertTrue(button.path("states").toString().contains("disabled"), button.path("states").toString());
                JsonNode boxes = button.path("boxes");
                assertEquals(200, boxes.path("document_css_px").path("y").asDouble(), 1e-9);
                assertEquals(200, boxes.path("frame_document").path("y").asDouble(), 1e-9);
                assertEquals(120, boxes.path("layout_viewport").path("y").asDouble(), 1e-9);
                assertEquals(110, boxes.path("visual_viewport").path("y").asDouble(), 1e-9);
                assertFalse(boxes.path("document_css_px").equals(boxes.path("layout_viewport")));
                JsonNode framed = node(json, "framed");
                assertTrue(framed.path("frame_path").asText().contains("iframe"), framed.toString());
                assertEquals(306, framed.path("boxes").path("document_css_px").path("y").asDouble(), 1e-9);
                assertEquals(6, framed.path("boxes").path("frame_document").path("y").asDouble(), 1e-9);
                SnapshotSchemas.assertValid(schema(), json);
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void fullPageFlagIsTheOnlyExtraScreenshotParameter() throws Exception {
        Path dir = Files.createTempDirectory("snap-out");
        try (FakeCdp cdp = FakeCdp.start()) {
            CdpConnection connection = CdpConnection.connect(
                    URI.create("ws://127.0.0.1:" + cdp.port + "/devtools/page/PAGE1"),
                    java.time.Duration.ofSeconds(5));
            try {
                JsonNode json = CdpSnapshotProducer.produce(connection, "s", java.util.Map.of(
                        "full-page", "true",
                        "out-dir", dir.toString()));
                assertTrue(Files.exists(dir.resolve("snapshot.json")));
                assertTrue(Files.exists(dir.resolve("screenshot.png")));
                assertTrue(json.path("screenshot").path("path").asText().endsWith("screenshot.png"));
                assertEquals(METHODS, cdp.methods);
            } finally {
                connection.close();
            }
        }
    }

    private static JsonNode schema() throws Exception {
        return Jsons.MAPPER.readTree(CompositeSnapshot.class.getResourceAsStream(
                "/dev/domengine/snapshot/composite-snapshot.schema.json"));
    }

    private static JsonNode node(JsonNode json, String id) {
        for (JsonNode node : json.path("nodes")) {
            if (id.equals(node.path("attributes").path("id").asText())) return node;
        }
        throw new AssertionError("missing " + id + " in " + json.path("nodes"));
    }
}
