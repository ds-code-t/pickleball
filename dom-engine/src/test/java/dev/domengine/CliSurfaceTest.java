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

class CliSurfaceTest {
    private final Path page = Path.of("src/test/resources/fixtures/page.html");

    @Test
    void fileCommandsMatchTheFixture() throws Exception {
        CliResult info = DomEngineMain.dispatch(List.of("info", page.toString()));
        assertEquals(0, info.exit(), info.stderr());
        assertTrue(Jsons.MAPPER.readTree(info.stdout()).path("nodes").asInt() > 1);

        CliResult resolved = DomEngineMain.dispatch(List.of("resolve", page.toString(), "--css", "#go"));
        assertEquals(0, resolved.exit(), resolved.stderr());
        assertEquals("button", Jsons.MAPPER.readTree(resolved.stdout()).path("nodes").get(0).path("node_name").asText().toLowerCase());

        CliResult recommended = DomEngineMain.dispatch(List.of("recommend", "--css", "#go"));
        assertEquals(0, recommended.exit(), recommended.stderr());
        assertEquals("css=#go", Jsons.MAPPER.readTree(recommended.stdout()).path("pipeline").asText());

        CliResult valid = DomEngineMain.dispatch(List.of("validate", page.toString(), "--css", "#go", "--expected", "1"));
        assertEquals(0, valid.exit(), valid.stderr());

        CliResult mismatch = DomEngineMain.dispatch(List.of("validate", page.toString(), "--css", "#go", "--expected", "0"));
        assertEquals(1, mismatch.exit(), mismatch.stderr());

        Path root = Files.createTempDirectory("conform");
        CliResult conform = DomEngineMain.dispatch(List.of("conform", "--root", root.toString()));
        assertEquals(0, conform.exit(), conform.stderr());
        JsonNode body = Jsons.MAPPER.readTree(conform.stdout());
        assertEquals(0, body.path("cases").asInt());
        assertTrue(body.path("ok").asBoolean());

        JsonNode caps = Jsons.MAPPER.readTree(DomEngineMain.dispatch(List.of("capabilities")).stdout());
        assertTrue(caps.path("commands").toString().contains("mcp"));
        assertTrue(caps.path("commands").toString().contains("resolve"));
    }
}
