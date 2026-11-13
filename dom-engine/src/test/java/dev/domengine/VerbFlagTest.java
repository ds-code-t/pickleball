package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.cli.VerbFlags;
import dev.domengine.json.Jsons;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerbFlagTest {
    @Test
    void capabilitiesMatchTheVerbSets() throws Exception {
        JsonNode caps = Jsons.MAPPER.readTree(DomEngineMain.dispatch(List.of("capabilities")).stdout());
        JsonNode listed = caps.path("verb_flags");
        assertEquals(VerbFlags.VERBS.size(), listed.size());
        for (var entry : VerbFlags.VERBS.entrySet()) {
            List<String> actual = new ArrayList<>();
            for (JsonNode item : listed.path(entry.getKey())) actual.add(item.asText());
            assertEquals(entry.getValue(), actual, entry.getKey());
        }
        assertTrue(listed.path("query").toString().contains("wait-for"));
        assertTrue(listed.path("query").toString().contains("within"));
    }

    @Test
    void unknownQueryFlagIsUsage() throws Exception {
        Path page = Files.createTempFile("verb-flag", ".html");
        Files.writeString(page, "<!DOCTYPE html><button id='go'>Go</button>");
        CliResult result = DomEngineMain.dispatch(List.of(
                "query", "--file", page.toString(), "--css", "#go", "--not-a-flag", "1"));
        assertEquals(2, result.exit(), result.stderr());
        JsonNode error = Jsons.MAPPER.readTree(result.stderr());
        assertEquals("usage", error.path("code").asText());
        assertTrue(error.path("error").asText().contains("unrecognized flag --not-a-flag for query"), error.toString());
    }

    @Test
    void knownCssQueryStillRuns() throws Exception {
        Path page = Files.createTempFile("verb-flag", ".html");
        Files.writeString(page, "<!DOCTYPE html><button id='go'>Go</button>");
        CliResult result = DomEngineMain.dispatch(List.of("query", "--file", page.toString(), "--css", "#go"));
        assertEquals(0, result.exit(), result.stderr());
        assertEquals("go", Jsons.MAPPER.readTree(result.stdout()).path("nodes").get(0).path("attributes").path("id").asText());
    }
}
