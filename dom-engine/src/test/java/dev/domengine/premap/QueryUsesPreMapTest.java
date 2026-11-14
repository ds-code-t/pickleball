package dev.domengine.premap;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.FakeCdp;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.log.EngineLog;
import dev.domengine.query.Query;
import dev.domengine.session.SessionRegistry;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryUsesPreMapTest {
    @TempDir
    Path temp;

    @AfterEach
    void clean() {
        PreMapLookup.resetForTests();
        SessionRegistry.resetForTests();
        EngineLog.resetForTests();
    }

    @Test
    void secondQuerySkipsTheDomAndUsesCandidates() throws Exception {
        Path logs = temp.resolve("logs");
        EngineLog.configure(Map.of("DOMENGINE_LOG", "premap", "DOMENGINE_LOG_DIR", logs.toString()));
        try (FakeCdp cdp = FakeCdp.start()) {
            CliResult created = DomEngineMain.dispatch(List.of(
                    "session", "attach", "--id", "q7", "--cdp", cdp.http(), "--i-accept-local-attach"));
            assertEquals(0, created.exit(), created.stderr());
            DomNode root = SessionRegistry.get().require("q7").tree().root;
            cdp.methods.clear();
            PreMapLookup.resetForTests();
            EngineLog.configure(Map.of("DOMENGINE_LOG", "premap", "DOMENGINE_LOG_DIR", logs.toString()));

            Map<String, String> flags = flags("v1");
            JsonNode first = Query.run(root, flags);
            assertTrue(first.path("premap").asText().startsWith("rebuilt"), first.toString());
            assertEquals(1, PreMapLookup.rebuildsForTests());
            assertTrue(cdp.methods.stream().anyMatch("DOMSnapshot.captureSnapshot"::equals), cdp.methods.toString());
            assertEquals(0, cdp.methods.stream().filter("DOM.getDocument"::equals).count(), cdp.methods.toString());
            assertTrue(cdp.methods.stream().noneMatch("Page.captureScreenshot"::equals), cdp.methods.toString());
            List<String> indexed = PreMapLookup.installed().candidates("role", "button").orElseThrow();
            List<String> handles = handles(first);
            assertFalse(indexed.isEmpty(), indexed.toString());
            assertEquals(indexed.size(), handles.size());
            assertTrue(indexed.containsAll(handles), handles + " vs " + indexed);

            cdp.methods.clear();
            JsonNode second = Query.run(root, flags);
            assertEquals("hit", second.path("premap").asText(), second.toString());
            assertEquals(0, cdp.methods.stream().filter(method -> method.startsWith("DOM.")).count(), cdp.methods.toString());
            assertEquals(handles, handles(second));
            assertEquals(1, PreMapLookup.rebuildsForTests());
            assertEquals(1, PreMapLookup.hitsForTests());

            flags.put("doc-version", "v2");
            cdp.methods.clear();
            JsonNode third = Query.run(root, flags);
            assertTrue(third.path("premap").asText().startsWith("rebuilt"), third.toString());
            assertEquals(2, PreMapLookup.rebuildsForTests());
            assertEquals(0, cdp.methods.stream().filter("DOM.getDocument"::equals).count(), cdp.methods.toString());
            assertEquals(1, cdp.methods.stream().filter("DOMSnapshot.captureSnapshot"::equals).count(), cdp.methods.toString());
        }
        String text = Files.readString(Files.list(logs).filter(path -> path.getFileName().toString().endsWith(".log")).findFirst().orElseThrow());
        assertTrue(text.contains("rebuild nodes="), text);
        assertTrue(text.contains("hit nodes="), text);
    }

    @Test
    void imageClauseCapturesOncePerVersion() throws Exception {
        try (FakeCdp cdp = FakeCdp.start()) {
            CliResult created = DomEngineMain.dispatch(List.of(
                    "session", "attach", "--id", "img", "--cdp", cdp.http(), "--i-accept-local-attach"));
            assertEquals(0, created.exit(), created.stderr());
            DomNode root = SessionRegistry.get().require("img").tree().root;
            cdp.methods.clear();
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("session", "img");
            flags.put("pipeline", "image=marker");
            flags.put("doc-version", "shot");
            flags.put("explain", "true");
            dev.domengine.cdp.CdpException first = org.junit.jupiter.api.Assertions.assertThrows(
                    dev.domengine.cdp.CdpException.class, () -> Query.run(root, flags));
            assertTrue(first.getMessage().contains("viewport"), first.getMessage());
            long shots = cdp.methods.stream().filter("Page.captureScreenshot"::equals).count();
            assertEquals(1, shots, cdp.methods.toString());
            cdp.methods.clear();
            org.junit.jupiter.api.Assertions.assertThrows(dev.domengine.cdp.CdpException.class, () -> Query.run(root, flags));
            assertEquals(0, cdp.methods.stream().filter("Page.captureScreenshot"::equals).count(), cdp.methods.toString());
            assertEquals(0, cdp.methods.stream().filter(method -> method.startsWith("DOM.")).count(), cdp.methods.toString());
        }
    }

    private static Map<String, String> flags(String version) {
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("session", "q7");
        flags.put("pipeline", "role=button");
        flags.put("doc-version", version);
        flags.put("explain", "true");
        return flags;
    }

    private static List<String> handles(JsonNode json) {
        List<String> handles = new ArrayList<>();
        for (JsonNode node : json.path("nodes")) handles.add(node.path("handle").asText());
        return handles;
    }
}
