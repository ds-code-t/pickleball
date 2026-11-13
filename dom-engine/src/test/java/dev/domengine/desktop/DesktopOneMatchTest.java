package dev.domengine.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopOneMatchTest {
    @AfterEach
    void reset() {
        DesktopTree.resetForTests();
    }

    @Test
    void ambiguousClickPrintsCandidates() throws Exception {
        Path fixture = Files.createTempFile("desktop-ambiguous", ".json");
        Files.writeString(fixture, """
                {"role":"window","name":"W","children":[
                  {"role":"button","name":"Go","x":1,"y":1,"width":10,"height":10},
                  {"role":"button","name":"Go","x":30,"y":1,"width":10,"height":10}
                ]}
                """);
        CliResult attached = DomEngineMain.dispatch(List.of(
                "desktop", "attach", "--id", "d", "--fixture", fixture.toString()
        ));
        assertEquals(0, attached.exit(), attached.stderr());
        CliResult click = DomEngineMain.dispatch(List.of("desktop", "click", "--id", "d", "--role", "button"));
        assertEquals(1, click.exit(), click.stderr());
        JsonNode error = Jsons.MAPPER.readTree(click.stderr());
        assertTrue(error.path("error").asText().startsWith("ambiguous: 2 candidates"), error.toString());
        assertEquals("ambiguous", error.path("code").asText());
        assertEquals(2, error.path("candidates").size());
        assertTrue(error.path("candidates").get(0).has("handle"));
        assertTrue(error.path("candidates").get(0).has("reason"));
        Files.deleteIfExists(fixture);
    }

    @Test
    void oneButtonStillClicks() throws Exception {
        Path fixture = Files.createTempFile("desktop-one", ".json");
        Files.writeString(fixture, """
                {"role":"window","name":"W","children":[
                  {"role":"button","name":"Save","x":4,"y":6,"width":20,"height":10}
                ]}
                """);
        CliResult attached = DomEngineMain.dispatch(List.of(
                "desktop", "attach", "--id", "one", "--fixture", fixture.toString()
        ));
        assertEquals(0, attached.exit(), attached.stderr());
        CliResult click = DomEngineMain.dispatch(List.of(
                "desktop", "click", "--id", "one", "--pipeline", "role=button[name=\"Save\"]"
        ));
        assertEquals(0, click.exit(), click.stderr());
        assertEquals("Save", Jsons.MAPPER.readTree(click.stdout()).path("name").asText());
        Files.deleteIfExists(fixture);
    }
}
