package dev.domengine.premap;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.FakeCdp;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.json.Jsons;
import dev.domengine.query.Query;
import dev.domengine.session.SessionRegistry;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreMapStalenessTest {
    @TempDir
    Path temp;

    @AfterEach
    void clean() {
        PreMapLookup.resetForTests();
        DesktopTree.resetForTests();
        SessionRegistry.resetForTests();
    }

    @Test
    void unreadVersionIsOneEvaluateAndAMutationRebuilds() throws Exception {
        try (FakeCdp cdp = FakeCdp.start()) {
            CliResult created = DomEngineMain.dispatch(List.of(
                    "session", "attach", "--id", "stale", "--cdp", cdp.http(), "--i-accept-local-attach"));
            assertEquals(0, created.exit(), created.stderr());
            DomNode root = SessionRegistry.get().require("stale").tree().root;
            cdp.methods.clear();
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("session", "stale");
            flags.put("pipeline", "role=button");
            flags.put("explain", "true");
            JsonNode first = Query.run(root, flags);
            assertTrue(first.path("premap").asText().startsWith("rebuilt"), first.toString());
            assertTrue(cdp.methods.contains("Runtime.evaluate"), cdp.methods.toString());
            cdp.methods.clear();
            JsonNode second = Query.run(root, flags);
            assertEquals("hit", second.path("premap").asText());
            assertEquals(0, cdp.methods.stream().filter(method -> method.startsWith("DOM.")).count(), cdp.methods.toString());
            assertTrue(cdp.methods.contains("Runtime.evaluate"), cdp.methods.toString());
        }
    }

    @Test
    void desktopSequenceBumpRebuildsOnce() throws Exception {
        Path fixture = temp.resolve("desk.json");
        Files.writeString(fixture, """
                {"role":"window","name":"Desk","children":[{"role":"button","name":"Save"}]}
                """);
        DesktopTree.attach("desk", fixture);
        JsonNode first = Jsons.MAPPER.readTree(Jsons.pretty(DesktopTree.query("desk", Map.of(
                "pipeline", "role=button", "explain", "true"))));
        assertTrue(first.path("premap").asText().startsWith("rebuilt"), first.toString());
        assertEquals(1, first.path("nodes").size(), first.toString());
        JsonNode second = Jsons.MAPPER.readTree(Jsons.pretty(DesktopTree.query("desk", Map.of(
                "pipeline", "role=button", "explain", "true"))));
        assertEquals("hit", second.path("premap").asText(), second.toString());
        assertEquals(1, PreMapLookup.rebuildsForTests());
        DesktopTree.walk("desk");
        JsonNode third = Jsons.MAPPER.readTree(Jsons.pretty(DesktopTree.query("desk", Map.of(
                "pipeline", "role=button", "explain", "true"))));
        assertTrue(third.path("premap").asText().startsWith("rebuilt"), third.toString());
        assertEquals(2, PreMapLookup.rebuildsForTests());
        assertEquals(1, PreMapLookup.hitsForTests());
    }
}
