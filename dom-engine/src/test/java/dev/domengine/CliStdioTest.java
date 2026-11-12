package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliStdioTest {
    @Test
    void stdioVersionAndRefusesNestedServe() throws Exception {
        String request = """
                {"id":"h1","argv":["version"]}
                {"id":"h2","argv":["serve","--stdio"]}
                """;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        DomEngineMain.serve(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), sink);
        String[] lines = sink.toString(StandardCharsets.UTF_8).lines().filter(line -> line.startsWith("{")).toArray(String[]::new);
        assertEquals(2, lines.length, sink.toString(StandardCharsets.UTF_8));
        JsonNode first = Jsons.MAPPER.readTree(lines[0]);
        assertEquals("h1", first.path("id").asText());
        assertEquals(0, first.path("exit").asInt());
        assertTrue(first.path("stdout").asText().contains("\"version\""));
        JsonNode second = Jsons.MAPPER.readTree(lines[1]);
        assertEquals(1, second.path("exit").asInt());
        assertTrue(second.path("stderr").asText().contains("invalid"));
    }
}
