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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire shapes the Workbench direct-control provider relies on, as the Rust
 * {@code composite} CLI produced them: {@code --session-dir} is a global flag on
 * every verb, and pretty JSON is {@code serde_json::to_string_pretty} layout.
 */
class RustCliShapeTest {
    @AfterEach
    void clean() {
        SessionRegistry.resetForTests();
    }

    @Test
    void sessionDirIsGlobalForEveryVerb() throws Exception {
        Path registry = Files.createTempDirectory("domengine-global-dir");
        Path other = Files.createTempDirectory("domengine-other-dir");
        try (FakeCdp cdp = FakeCdp.start()) {
            CliResult attached = DomEngineMain.dispatch(List.of(
                    "session", "attach", "--cdp", cdp.http(), "--id", "g1", "--i-accept-local-attach",
                    "--session-dir", registry.toString()));
            assertEquals(0, attached.exit(), attached.stderr());

            // Point the process at a different registry, then name the first one globally.
            assertEquals(0, DomEngineMain.dispatch(List.of("session", "list", "--session-dir", other.toString())).exit());
            CliResult before = DomEngineMain.dispatch(List.of(
                    "--session-dir", registry.toString(), "query", "--session", "g1", "--css", "#go"));
            assertEquals(0, before.exit(), before.stderr() + before.stdout());
            assertEquals(1, Jsons.MAPPER.readTree(before.stdout()).path("nodes").size(), before.stdout());

            assertEquals(0, DomEngineMain.dispatch(List.of("session", "list", "--session-dir", other.toString())).exit());
            CliResult after = DomEngineMain.dispatch(List.of(
                    "query", "--session", "g1", "--css", "#go", "--session-dir", registry.toString()));
            assertEquals(0, after.exit(), after.stderr() + after.stdout());

            CliResult closed = DomEngineMain.dispatch(List.of(
                    "--session-dir=" + registry, "session", "close", "--id", "g1"));
            assertEquals(0, closed.exit(), closed.stderr());
            JsonNode body = Jsons.MAPPER.readTree(closed.stdout());
            assertTrue(body.path("closed").asBoolean(), closed.stdout());
            assertFalse(body.path("killed").asBoolean(), closed.stdout());
        }
    }

    @Test
    void prettyJsonMatchesSerdeLayout() {
        var node = Jsons.object();
        node.put("ok", true);
        node.put("launched", false);
        node.putArray("allowlist");
        node.putObject("meta");
        node.putArray("ids").add("a").add("b");
        assertEquals("""
                {
                  "ok": true,
                  "launched": false,
                  "allowlist": [],
                  "meta": {},
                  "ids": [
                    "a",
                    "b"
                  ]
                }
                """, Jsons.pretty(node));
    }
}
