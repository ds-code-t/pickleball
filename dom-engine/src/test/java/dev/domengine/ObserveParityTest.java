package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.action.Observe;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.image.TemplateMatch;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import dev.domengine.session.SessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObserveParityTest {
    private final Path page = Path.of("src/test/resources/fixtures/page.html");

    @AfterEach
    void clean() {
        SessionRegistry.resetForTests();
        DesktopTree.resetForTests();
    }

    @Test
    void waitFindsTheOfflineButtonAndTimesOutImmediately() throws Exception {
        CliResult found = DomEngineMain.dispatch(List.of(
                "observe", "--file", page.toString(), "--css", "#go", "--timeout-ms", "200"
        ));
        assertEquals(0, found.exit(), found.stderr());
        JsonNode body = Jsons.MAPPER.readTree(found.stdout());
        assertTrue(body.path("waited").asBoolean());
        assertEquals("button", body.path("nodes").get(0).path("node_name").asText().toLowerCase());

        CliResult missing = DomEngineMain.dispatch(List.of(
                "wait", "--file", page.toString(), "--css", "#missing", "--timeout-ms", "0"
        ));
        assertEquals(1, missing.exit(), missing.stderr());
        assertEquals("timeout", Jsons.MAPPER.readTree(missing.stderr()).path("code").asText());

        var root = HtmlTree.parse(Files.readString(page));
        JsonNode direct = Observe.waitFor(root, Map.of("css", "#go", "timeout-ms", "0"));
        assertEquals(1, direct.path("nodes").size());
    }

    @Test
    void identicalTemplateScoresOneAndTheImageStageHitsTheBox() throws Exception {
        String pgm = "P2\n2 2\n255\n10 20\n30 40\n";
        TemplateMatch.Gray image = TemplateMatch.parse(pgm);
        assertEquals(1.0, TemplateMatch.score(image, image), 0.0001);

        Path dir = Files.createTempDirectory("domengine-image");
        Path viewport = dir.resolve("view.pgm");
        Path template = dir.resolve("mark.pgm");
        Files.writeString(viewport, pgm);
        Files.writeString(template, pgm);
        Path html = dir.resolve("page.html");
        Files.writeString(html, """
                <html><body>
                <div id="mark" data-x="0" data-y="0" data-w="2" data-h="2">mark</div>
                </body></html>
                """);

        CliResult query = DomEngineMain.dispatch(List.of(
                "query", "--file", html.toString(),
                "--pipeline", "image=" + template,
                "--viewport", viewport.toString()
        ));
        assertEquals(0, query.exit(), query.stderr());
        JsonNode hit = Jsons.MAPPER.readTree(query.stdout());
        assertEquals("div", hit.path("nodes").get(0).path("node_name").asText().toLowerCase());
        assertEquals(1.0, hit.path("image").path("confidence").asDouble(), 0.0001);

        CliResult bare = DomEngineMain.dispatch(List.of(
                "query", "--file", html.toString(), "--pipeline", "image=" + template
        ));
        assertEquals(1, bare.exit(), bare.stderr());
        assertTrue(Jsons.MAPPER.readTree(bare.stderr()).path("error").asText().contains("viewport"));

        CliResult ocr = DomEngineMain.dispatch(List.of(
                "query", "--file", html.toString(), "--pipeline", "image=ocr:mark", "--viewport", viewport.toString()
        ));
        assertEquals(1, ocr.exit(), ocr.stderr());
        assertTrue(Jsons.MAPPER.readTree(ocr.stderr()).path("error").asText().contains("ocr is not available"));
    }

    @Test
    void desktopFixtureQueryClickAndType() throws Exception {
        Path fixture = Files.createTempFile("desktop", ".json");
        Files.writeString(fixture, """
                {"role":"window","name":"Fixture","children":[
                  {"role":"button","name":"Save","x":4,"y":6,"width":20,"height":10},
                  {"role":"textbox","name":"Title","x":4,"y":30,"width":80,"height":16}
                ]}
                """);
        assertEquals(0, DomEngineMain.dispatch(List.of(
                "desktop", "attach", "--id", "desk", "--fixture", fixture.toString()
        )).exit());
        CliResult queried = DomEngineMain.dispatch(List.of(
                "desktop", "query", "--id", "desk", "--pipeline", "role=button[name=\"Save\"]"
        ));
        assertEquals(0, queried.exit(), queried.stderr());
        assertEquals("button", Jsons.MAPPER.readTree(queried.stdout()).path("nodes").get(0).path("role").asText());

        CliResult clicked = DomEngineMain.dispatch(List.of(
                "desktop", "click", "--id", "desk", "--pipeline", "role=button[name=\"Save\"]"
        ));
        assertEquals(0, clicked.exit(), clicked.stderr());
        assertEquals("Save", Jsons.MAPPER.readTree(clicked.stdout()).path("name").asText());
        assertEquals("fixture", Jsons.MAPPER.readTree(clicked.stdout()).path("backend").asText());

        CliResult typed = DomEngineMain.dispatch(List.of(
                "desktop", "type", "--id", "desk", "--pipeline", "role=textbox[name=\"Title\"]", "--text", "Hello"
        ));
        assertEquals(0, typed.exit(), typed.stderr());
        assertEquals("Hello", Jsons.MAPPER.readTree(typed.stdout()).path("text").asText());

        CliResult caps = DomEngineMain.dispatch(List.of("capabilities"));
        assertTrue(Jsons.MAPPER.readTree(caps.stdout()).path("actions").toString().contains("dblclick"));
        assertTrue(Jsons.MAPPER.readTree(caps.stdout()).path("desktop").toString().contains("attach"));
        Files.deleteIfExists(fixture);
    }
}
