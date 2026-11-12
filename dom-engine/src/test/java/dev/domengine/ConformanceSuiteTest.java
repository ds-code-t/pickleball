package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.conform.Conformance;
import dev.domengine.json.Jsons;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConformanceSuiteTest {
    @Test
    void offlineSuiteMatchesTheRustFixturesWhereTheHtmlCan() throws Exception {
        Path root = Path.of("src/test/resources/conformance");
        JsonNode result = Conformance.run(root);
        assertEquals(0, result.path("failed").asInt(), result.path("failures").toString());
        assertTrue(result.path("cases").asInt() >= 100, result.toString());
        assertTrue(result.path("checked").asInt() > 100, result.toString());

        CliResult cli = DomEngineMain.dispatch(List.of("conform", "--root", root.toString()));
        assertEquals(0, cli.exit(), cli.stderr());
        assertEquals(result.path("cases").asInt(), Jsons.MAPPER.readTree(cli.stdout()).path("cases").asInt());
    }
}
