package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.session.SessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionSmokeTest {
    @AfterEach
    void clean() {
        SessionRegistry.resetForTests();
    }

    @Test
    void actionsReachTheCdpFixture() throws Exception {
        try (FakeCdp cdp = FakeCdp.start()) {
            CliResult attached = DomEngineMain.dispatch(List.of(
                    "session", "attach", "--id", "live", "--cdp", cdp.http(), "--i-accept-local-attach"
            ));
            assertEquals(0, attached.exit(), attached.stderr());

            assertOk(DomEngineMain.dispatch(List.of("click", "--session", "live", "--css", "#go")));
            assertTrue(cdp.methods.contains("Input.dispatchMouseEvent"), cdp.methods.toString());

            assertOk(DomEngineMain.dispatch(List.of("type", "--session", "live", "--css", "#go", "--text", "hi")));
            assertTrue(cdp.methods.contains("Input.insertText"));

            assertOk(DomEngineMain.dispatch(List.of("key", "--session", "live", "--css", "#go", "--key", "ENTER")));
            assertTrue(cdp.methods.contains("Input.dispatchKeyEvent"));
            assertTrue(cdp.keys.contains("Enter"), cdp.keys.toString());

            assertOk(DomEngineMain.dispatch(List.of("key", "--session", "live", "--css", "#go", "--key", "a")));
            assertTrue(cdp.texts.contains("a"), cdp.texts.toString());

            assertOk(DomEngineMain.dispatch(List.of("key", "--session", "live", "--css", "#go", "--key", "CONTROL+A")));
            assertTrue(cdp.modifiers.contains(2), cdp.modifiers.toString());
            assertTrue(cdp.keys.contains("Control"), cdp.keys.toString());

            assertOk(DomEngineMain.dispatch(List.of(
                    "drag", "--session", "live",
                    "--source", "{\"css\":\"#host\"}",
                    "--target", "{\"css\":\"#go\"}"
            )));

            assertOk(DomEngineMain.dispatch(List.of("scroll", "--session", "live", "--css", "#go", "--dx", "0", "--dy", "40")));
            assertOk(DomEngineMain.dispatch(List.of("dblclick", "--session", "live", "--css", "#go")));
            assertOk(DomEngineMain.dispatch(List.of("contextclick", "--session", "live", "--css", "#go")));
            assertOk(DomEngineMain.dispatch(List.of("submit", "--session", "live", "--css", "#go")));
            assertTrue(cdp.methods.contains("Runtime.callFunctionOn"));

            CliResult eval = DomEngineMain.dispatch(List.of(
                    "eval", "--session", "live", "--css", "#go", "--source", "function(){return this.tagName}"
            ));
            assertOk(eval);
            assertEquals("BUTTON", Jsons.MAPPER.readTree(eval.stdout()).path("value").asText());

            assertOk(DomEngineMain.dispatch(List.of(
                    "drag", "--session", "live", "--source", "#host", "--target", "#go"
            )));

            Path out = Files.createTempFile("domengine-shot", ".png");
            assertOk(DomEngineMain.dispatch(List.of("screenshot", "--session", "live", "--out", out.toString())));
            assertTrue(cdp.methods.contains("Page.captureScreenshot"));
            assertTrue(Files.size(out) > 0);
            Files.deleteIfExists(out);
        }
    }

    @Test
    void ambiguousClickIsRejected() throws Exception {
        try (FakeCdp cdp = FakeCdp.start()) {
            assertOk(DomEngineMain.dispatch(List.of(
                    "session", "attach", "--id", "live", "--cdp", cdp.http(), "--i-accept-local-attach"
            )));
            CliResult click = DomEngineMain.dispatch(List.of("click", "--session", "live", "--css", "*"));
            assertEquals(1, click.exit(), click.stderr());
            JsonNode error = Jsons.MAPPER.readTree(click.stderr());
            assertEquals("ambiguous", error.path("code").asText());
        }
    }

    private static void assertOk(CliResult result) {
        assertEquals(0, result.exit(), result.stderr());
    }
}
