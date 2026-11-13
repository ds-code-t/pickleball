package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeSnapshotModelTest {
    @Test
    void offlineSnapshotRoundTripsAndBindsLabels() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/fixtures/affinity-form.html"));
        DomNode root = HtmlTree.parse(html);
        JsonNode json = CompositeSnapshots.offline(root, SnapshotOptions.fromFlags(java.util.Map.of(
                "file", "src/test/resources/fixtures/affinity-form.html")));
        assertEquals("pickleball.composite-snapshot", json.path("schema").asText());
        assertEquals(1, json.path("version").asInt());
        assertEquals("fixture", json.path("transport").asText());
        assertTrue(json.path("premap").path("sections").size() > 0);
        assertTrue(json.path("premap").path("labels").size() > 0, json.path("premap").path("labels").toString());
        assertFalse(json.path("source").path("text").asText().isBlank());
        JsonNode again = CompositeSnapshot.fromJson(json).toJson();
        assertEquals(json, again);
    }

    @Test
    void imagePixelsFollowTheScreenshotScale() throws Exception {
        Path png = Files.createTempFile("shot", ".png");
        Files.write(png, Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="));
        String html = "<html data-w=\"100\" data-h=\"50\"><body><button data-x=\"10\" data-y=\"4\" data-w=\"20\" data-h=\"8\">Go</button></body></html>";
        DomNode root = HtmlTree.parse(html);
        JsonNode json = CompositeSnapshots.offline(root, SnapshotOptions.fromFlags(java.util.Map.of(
                "screenshot", png.toString())));
        assertEquals(1, json.path("screenshot").path("width_px").asInt());
        assertEquals(0.01, json.path("screenshot").path("image_scale").asDouble(), 1e-9);
        JsonNode button = null;
        for (JsonNode node : json.path("nodes")) {
            if ("button".equals(node.path("tag").asText())) button = node;
        }
        assertTrue(button != null);
        assertEquals(0.1, button.path("boxes").path("image_px").path("x").asDouble(), 1e-9);
        assertEquals(0.2, button.path("boxes").path("image_px").path("width").asDouble(), 1e-9);
        assertTrue(button.path("boxes").path("visual_viewport").isNull());
        assertTrue(button.path("boxes").path("document_css_px").isObject());
    }
}
