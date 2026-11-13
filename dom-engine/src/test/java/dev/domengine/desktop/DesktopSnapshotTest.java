package dev.domengine.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.snapshot.CompositeSnapshot;
import dev.domengine.snapshot.SnapshotSchemas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSnapshotTest {
    @AfterEach
    void reset() {
        DesktopTree.resetForTests();
    }

    @Test
    void fixtureNeedsAScreenshotAndThenValidates() throws Exception {
        Path fixture = Files.createTempFile("desktop-snap", ".json");
        Files.writeString(fixture, """
                {"role":"window","name":"W","x":0,"y":0,"width":100,"height":50,"children":[
                  {"role":"button","name":"Go","x":1,"y":1,"width":10,"height":10}
                ]}
                """);
        CliResult attached = DomEngineMain.dispatch(List.of(
                "desktop", "attach", "--id", "d", "--fixture", fixture.toString()));
        assertEquals(0, attached.exit(), attached.stderr());
        CliResult missing = DomEngineMain.dispatch(List.of("desktop", "snapshot", "--id", "d"));
        assertEquals(1, missing.exit(), missing.stderr());
        assertTrue(missing.stderr().contains("error: desktop fixture snapshot needs --screenshot"), missing.stderr());

        Path png = Files.createTempFile("shot", ".png");
        Files.write(png, Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="));
        CliResult shot = DomEngineMain.dispatch(List.of(
                "desktop", "snapshot", "--id", "d", "--screenshot", png.toString()));
        assertEquals(0, shot.exit(), shot.stderr());
        JsonNode json = Jsons.MAPPER.readTree(shot.stdout());
        JsonNode schema = Jsons.MAPPER.readTree(CompositeSnapshot.class.getResourceAsStream(
                "/dev/domengine/snapshot/composite-snapshot.schema.json"));
        SnapshotSchemas.assertValid(schema, json);
        assertEquals("a11y-xml", json.path("source").path("kind").asText());
        assertEquals("desktop:0", json.path("doc_version").asText());
        assertTrue(json.path("premap").path("sections").size() > 0);
        JsonNode button = null;
        for (JsonNode node : json.path("nodes")) {
            if ("button".equals(node.path("role").asText())) button = node;
        }
        assertTrue(button != null, json.path("nodes").toString());
        assertEquals(10, button.path("boxes").path("screen_px").path("width").asDouble(), 0);
        assertTrue(button.path("boxes").path("image_px").isObject());
        assertTrue(button.path("xpath").asText().contains("["));

        CliResult click = DomEngineMain.dispatch(List.of("desktop", "click", "--id", "d", "--role", "button"));
        assertEquals(0, click.exit(), click.stderr());
        CliResult again = DomEngineMain.dispatch(List.of(
                "desktop", "snapshot", "--id", "d", "--screenshot", png.toString()));
        assertEquals("desktop:1", Jsons.MAPPER.readTree(again.stdout()).path("doc_version").asText());
        assertThrows(CdpException.class, () -> DesktopTree.snapshot("missing", java.util.Map.of()));
    }
}
