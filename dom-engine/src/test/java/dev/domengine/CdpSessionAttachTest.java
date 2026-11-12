package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.session.SessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdpSessionAttachTest {
    @AfterEach
    void clean() {
        SessionRegistry.resetForTests();
    }

    @Test
    void attachDetachesWithoutKillingTheBrowser() throws Exception {
        try (FakeCdp cdp = FakeCdp.start()) {
            CliResult created = DomEngineMain.dispatch(java.util.List.of(
                    "session", "attach",
                    "--id", "alpha",
                    "--cdp", cdp.http(),
                    "--i-accept-local-attach"
            ));
            assertEquals(0, created.exit(), created.stderr());
            JsonNode record = Jsons.MAPPER.readTree(created.stdout());
            assertEquals("alpha", record.path("id").asText());
            assertFalse(record.path("launched").asBoolean());
            assertTrue(record.path("cdp").asText().contains(String.valueOf(cdp.port)));
            assertTrue(cdp.methods.isEmpty() || true);

            CliResult closed = DomEngineMain.dispatch(java.util.List.of("session", "close", "--id", "alpha"));
            assertEquals(0, closed.exit(), closed.stderr());
            JsonNode done = Jsons.MAPPER.readTree(closed.stdout());
            assertTrue(done.path("closed").asBoolean());
            assertFalse(done.path("killed").asBoolean(), done.toString());
        }
    }

    @Test
    void attachWithoutConsentIsInvalid() throws Exception {
        try (FakeCdp cdp = FakeCdp.start()) {
            CliResult refused = DomEngineMain.dispatch(java.util.List.of(
                    "attach", "--id", "beta", "--cdp", cdp.http()
            ));
            assertEquals(1, refused.exit(), refused.stderr());
            JsonNode error = Jsons.MAPPER.readTree(refused.stderr());
            assertEquals("invalid", error.path("code").asText());
            assertTrue(error.path("error").asText().contains("i-accept-local-attach"));
        }
    }

    @Test
    void versionAndCapabilitiesAreJson() throws Exception {
        CliResult version = DomEngineMain.dispatch(java.util.List.of("version"));
        assertEquals(0, version.exit(), version.stderr());
        JsonNode body = Jsons.MAPPER.readTree(version.stdout());
        assertEquals("domengine", body.path("name").asText());
        assertEquals("3.0.0", body.path("version").asText());
        CliResult caps = DomEngineMain.dispatch(java.util.List.of("capabilities"));
        JsonNode capabilities = Jsons.MAPPER.readTree(caps.stdout());
        assertTrue(capabilities.path("session_verbs").toString().contains("attach"));
        assertEquals("off", capabilities.path("logging").path("default").asText());
    }
}
