package dev.domengine;

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

/** Parsed-tree XPath stays the small subset. Anything else is an error, not zero hits. */
class OfflineXPathTest {
    @Test
    void attributeXpathStillMatches() throws Exception {
        Path page = page("<button id='go'>Go</button>");
        JsonNode found = query(page, "//button[@id='go']");
        assertEquals("go", found.path("nodes").get(0).path("attributes").path("id").asText());
    }

    @Test
    void descendantPathIsInvalidInsteadOfZeroHits() throws Exception {
        Path page = page("<ul><li><a id='inner'>x</a></li></ul>");
        CliResult result = DomEngineMain.dispatch(List.of("query", "--file", page.toString(), "--xpath", "//li/a"));
        assertEquals(1, result.exit(), result.stderr());
        assertTrue(result.stderr().contains("invalid"), result.stderr());
        JsonNode error = Jsons.MAPPER.readTree(result.stderr());
        assertEquals("invalid", error.path("code").asText());
    }

    @Test
    void brokenExpressionIsInvalid() throws Exception {
        Path page = page("<button id='go'>Go</button>");
        CliResult result = DomEngineMain.dispatch(List.of("query", "--file", page.toString(), "--xpath", "//*["));
        assertEquals(1, result.exit(), result.stderr());
        assertEquals("invalid", Jsons.MAPPER.readTree(result.stderr()).path("code").asText());
    }

    private static JsonNode query(Path page, String xpath) throws Exception {
        CliResult result = DomEngineMain.dispatch(List.of("query", "--file", page.toString(), "--xpath", xpath));
        assertEquals(0, result.exit(), result.stderr());
        return Jsons.MAPPER.readTree(result.stdout());
    }

    private static Path page(String body) throws Exception {
        Path file = Files.createTempFile("offline-xpath", ".html");
        Files.writeString(file, "<!DOCTYPE html><html><body>" + body + "</body></html>");
        return file;
    }
}
