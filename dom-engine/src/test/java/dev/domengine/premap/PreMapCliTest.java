package dev.domengine.premap;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreMapCliTest {
    @Test
    void premapFilePrintsSectionsAndLabels() throws Exception {
        CliResult result = DomEngineMain.dispatch(List.of(
                "premap", "--file", "src/test/resources/fixtures/affinity-form.html"));
        assertEquals(0, result.exit(), result.stderr());
        JsonNode json = Jsons.MAPPER.readTree(result.stdout());
        assertEquals("pickleball.composite-snapshot", json.path("schema").asText());
        assertTrue(json.path("premap").path("sections").size() > 0);
        assertTrue(json.path("premap").path("labels").size() > 0, json.path("premap").path("labels").toString());
        assertEquals(1, json.path("version").asInt());
    }

    @Test
    void missingFileIsUsage() throws Exception {
        CliResult result = DomEngineMain.dispatch(List.of("premap"));
        assertEquals(2, result.exit(), result.stderr());
        assertEquals("usage", Jsons.MAPPER.readTree(result.stderr()).path("code").asText());
    }

    @Test
    void outDirWritesTheSnapshot() throws Exception {
        Path dir = Files.createTempDirectory("premap");
        CliResult result = DomEngineMain.dispatch(List.of(
                "premap", "--file", "src/test/resources/fixtures/affinity-form.html", "--out-dir", dir.toString()));
        assertEquals(0, result.exit(), result.stderr());
        assertTrue(Files.size(dir.resolve("snapshot.json")) > 0);
    }
}
