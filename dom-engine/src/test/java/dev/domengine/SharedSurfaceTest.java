package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.json.Jsons;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Browser file query and desktop fixture query share one pipeline. Platform exceptions stay explicit. */
class SharedSurfaceTest {
    private static final String PIPELINE = "role=button[name=\"Save\"]";
    private final Path page = Path.of("src/test/resources/fixtures/page.html");

    @AfterEach
    void clean() {
        DesktopTree.resetForTests();
    }

    @Test
    void browserAndDesktopUseTheSameQueryResolveAndRejectTheSameBrowserOnlyStages() throws Exception {
        CliResult browser = DomEngineMain.dispatch(List.of(
                "query", "--file", page.toString(), "--pipeline", PIPELINE
        ));
        assertEquals(0, browser.exit(), browser.stderr());
        assertEquals("button", role(browser));

        CliResult browserResolved = DomEngineMain.dispatch(List.of(
                "resolve", "--file", page.toString(), "--pipeline", PIPELINE
        ));
        assertEquals(0, browserResolved.exit(), browserResolved.stderr());
        assertEquals("button", role(browserResolved));

        Path fixture = Files.createTempFile("desktop", ".json");
        Files.writeString(fixture, """
                {"role":"window","name":"Fixture","children":[
                  {"role":"button","name":"Save","x":4,"y":6,"width":20,"height":10}
                ]}
                """);
        assertEquals(0, DomEngineMain.dispatch(List.of(
                "desktop", "attach", "--id", "shared", "--fixture", fixture.toString()
        )).exit());
        CliResult desktop = DomEngineMain.dispatch(List.of(
                "desktop", "query", "--id", "shared", "--pipeline", PIPELINE
        ));
        assertEquals(0, desktop.exit(), desktop.stderr());
        assertEquals("button", role(desktop));
        assertEquals(role(browser), role(desktop));

        CliResult desktopResolved = DomEngineMain.dispatch(List.of(
                "desktop", "resolve", "--id", "shared", "--pipeline", PIPELINE
        ));
        assertEquals(0, desktopResolved.exit(), desktopResolved.stderr());
        JsonNode resolved = Jsons.MAPPER.readTree(desktopResolved.stdout());
        assertEquals("button", resolved.path("nodes").get(0).path("role").asText());
        assertEquals("button", resolved.path("node").path("role").asText());
        assertEquals("fixture", resolved.path("backend").asText());

        CliResult walked = DomEngineMain.dispatch(List.of("desktop", "walk", "--id", "shared"));
        assertEquals(0, walked.exit(), walked.stderr());
        assertTrue(walked.stdout().contains("Save"), walked.stdout());

        Path viewport = Files.createTempFile("view", ".pgm");
        Files.writeString(viewport, "P2\n1 1\n255\n0\n");
        CliResult browserOcr = DomEngineMain.dispatch(List.of(
                "query", "--file", page.toString(), "--pipeline", "image=ocr:mark", "--viewport", viewport.toString()
        ));
        CliResult desktopOcr = DomEngineMain.dispatch(List.of(
                "desktop", "query", "--id", "shared", "--pipeline", "image=ocr:mark", "--viewport", viewport.toString()
        ));
        assertEquals(1, browserOcr.exit(), browserOcr.stderr());
        assertEquals(1, desktopOcr.exit(), desktopOcr.stderr());
        String browserError = Jsons.MAPPER.readTree(browserOcr.stderr()).path("error").asText();
        String desktopError = Jsons.MAPPER.readTree(desktopOcr.stderr()).path("error").asText();
        assertTrue(browserError.contains("ocr is not available"), browserError);
        assertEquals(browserError, desktopError);

        CliResult pierced = DomEngineMain.dispatch(List.of(
                "query", "--file", page.toString(), "--xpath", "//span[@id='inner']", "--pierce", "open"
        ));
        assertEquals(0, pierced.exit(), pierced.stderr());
        assertEquals("inner", Jsons.MAPPER.readTree(pierced.stdout()).path("nodes").get(0).path("attributes").path("id").asText());
        CliResult desktopPierce = DomEngineMain.dispatch(List.of(
                "desktop", "query", "--id", "shared", "--pipeline", PIPELINE, "--pierce", "open"
        ));
        assertEquals(1, desktopPierce.exit(), desktopPierce.stderr());
        assertTrue(desktopPierce.stderr().contains("pierce is browser-only"), desktopPierce.stderr());

        Files.deleteIfExists(fixture);
        Files.deleteIfExists(viewport);
    }

    private static String role(CliResult result) throws Exception {
        return Jsons.MAPPER.readTree(result.stdout()).path("nodes").get(0).path("role").asText();
    }
}
