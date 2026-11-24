package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import dev.domengine.snapshot.CompositeSnapshots;
import dev.domengine.snapshot.IdentityChain;
import dev.domengine.snapshot.SnapshotContext;
import dev.domengine.snapshot.SnapshotOptions;
import dev.domengine.snapshot.XPathIndex;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code query --identity} replays a recorded chain through {@link DomEngineMain#dispatch}. */
class IdentityReplayCliTest {
    private static final String RECORDED = """
            <html data-w="200" data-h="100"><body>
              <button id="save" data-x="10" data-y="10" data-w="40" data-h="16">Save</button>
            </body></html>
            """;
    private static final String MUTATED = """
            <html data-w="200" data-h="100"><body>
              <button data-x="10" data-y="10" data-w="40" data-h="16">Save</button>
            </body></html>
            """;

    @AfterEach
    void clean() {
        DesktopTree.resetForTests();
    }

    @Test
    void replayAfterTheStableAttributeIsGone() throws Exception {
        DomNode recorded = HtmlTree.parse(RECORDED);
        JsonNode nodes = CompositeSnapshots.offline(recorded, SnapshotOptions.fromFlags(Map.of())).path("nodes");
        JsonNode save = node(nodes, "save");
        assertEquals("stable", save.path("identity").path("chain").get(0).path("kind").asText());
        Path page = Files.createTempFile("identity-replay", ".html");
        Files.writeString(page, MUTATED);
        Path chain = Files.createTempFile("identity-chain", ".json");
        Files.writeString(chain, save.toString());

        CliResult result = DomEngineMain.dispatch(List.of(
                "query", "--file", page.toString(), "--identity", chain.toString()));
        assertEquals(0, result.exit(), result.stderr());
        JsonNode replay = Jsons.MAPPER.readTree(result.stdout());
        assertEquals("fingerprint", replay.path("matched").asText(), replay.toString());
        DomNode button = element(HtmlTree.parse(MUTATED), "button");
        assertEquals(button.id(), replay.path("handle").asText(), replay.toString());
        assertEquals(button.backendNodeId, replay.path("backend_node_id").asLong());
        assertFalse(replay.path("locator").asText().isBlank());
        assertTrue(replay.path("index").asInt() > 0, replay.toString());

        CliResult inline = DomEngineMain.dispatch(List.of(
                "query", "--file", page.toString(), "--identity=" + Jsons.compact(save.path("identity"))));
        assertEquals(0, inline.exit(), inline.stderr());
        assertEquals(button.id(), Jsons.MAPPER.readTree(inline.stdout()).path("handle").asText());

        CliResult plain = DomEngineMain.dispatch(List.of("query", "--file", page.toString(), "--css", "button"));
        assertEquals(0, plain.exit(), plain.stderr());
        JsonNode plainNode = Jsons.MAPPER.readTree(plain.stdout()).path("nodes").get(0);
        assertEquals("BUTTON", plainNode.path("node_name").asText(), plainNode.toString());
        assertEquals("Save", plainNode.path("text").asText());
    }

    @Test
    void malformedIdentityIsAnError() throws Exception {
        Path page = Files.createTempFile("identity-bad", ".html");
        Files.writeString(page, MUTATED);
        CliResult broken = DomEngineMain.dispatch(List.of(
                "query", "--file", page.toString(), "--identity", "{not json"));
        assertEquals(1, broken.exit(), broken.stderr());
        JsonNode error = Jsons.MAPPER.readTree(broken.stderr());
        assertEquals("invalid", error.path("code").asText(), error.toString());
        assertTrue(error.path("error").asText().contains("invalid identity"), error.toString());
        assertFalse(error.path("ok").asBoolean());

        CliResult object = DomEngineMain.dispatch(List.of(
                "query", "--file", page.toString(), "--identity", "{}"));
        assertEquals(1, object.exit(), object.stderr());
        assertEquals("invalid", Jsons.MAPPER.readTree(object.stderr()).path("code").asText());

        CliResult missing = DomEngineMain.dispatch(List.of(
                "query", "--file", page.toString(), "--identity", page.resolveSibling("missing-identity-chain.json").toString()));
        assertEquals(1, missing.exit(), missing.stderr());
        assertEquals("invalid", Jsons.MAPPER.readTree(missing.stderr()).path("code").asText());
    }

    @Test
    void desktopQueryReplaysTheChainAfterTheStableAttributeIsGone() throws Exception {
        String recordedJson = """
                {"role":"window","name":"Desk","id":10,"children":[
                  {"role":"button","name":"Save","id":22,"attributes":{"id":"save"}}
                ]}
                """;
        String mutatedJson = """
                {"role":"window","name":"Desk","id":10,"children":[
                  {"role":"button","name":"Save","id":22}
                ]}
                """;
        DomNode recorded = DesktopTree.project(Jsons.MAPPER.readTree(recordedJson));
        recorded.linkParents();
        XPathIndex.assign(recorded);
        List<ObjectNode> nodes = new ArrayList<>();
        collect(recorded, nodes);
        IdentityChain.attach(recorded, nodes, SnapshotContext.desktop(
                recorded, SnapshotOptions.fromFlags(Map.of()), "", "linux", "fixture", "desktop:0"));
        ObjectNode save = null;
        for (ObjectNode node : nodes) {
            if ("save".equals(node.path("attributes").path("id").asText())) save = node;
        }
        assertTrue(save != null && save.path("identity").path("chain").get(0).path("kind").asText().equals("stable"),
                String.valueOf(nodes));
        Path chain = Files.createTempFile("desktop-identity", ".json");
        Files.writeString(chain, save.toString());
        Path fixture = Files.createTempFile("desktop-identity", ".json");
        Files.writeString(fixture, mutatedJson);
        assertEquals(0, DomEngineMain.dispatch(List.of(
                "desktop", "attach", "--id", "identity-desk", "--fixture", fixture.toString()
        )).exit());
        CliResult result = DomEngineMain.dispatch(List.of(
                "desktop", "query", "--id", "identity-desk", "--identity", chain.toString()));
        assertEquals(0, result.exit(), result.stderr());
        JsonNode replay = Jsons.MAPPER.readTree(result.stdout());
        assertEquals("fingerprint", replay.path("matched").asText(), replay.toString());
        DomNode live = DesktopTree.project(Jsons.MAPPER.readTree(mutatedJson));
        live.linkParents();
        assertEquals(named(live, "Save").id(), replay.path("handle").asText(), replay.toString());
        assertEquals(22L, replay.path("backend_node_id").asLong());
        assertEquals("fixture", replay.path("backend").asText());
    }

    private static JsonNode node(JsonNode nodes, String id) {
        for (JsonNode node : nodes) {
            if (id.equals(node.path("attributes").path("id").asText())) return node;
        }
        throw new AssertionError("missing " + id);
    }

    private static void collect(DomNode node, List<ObjectNode> nodes) {
        if (node == null || node.nodeType != 1) return;
        ObjectNode json = Jsons.object();
        json.put("handle", node.id());
        ObjectNode attributes = json.putObject("attributes");
        node.attributes.forEach(attributes::put);
        nodes.add(json);
        for (DomNode child : node.children) collect(child, nodes);
    }

    private static DomNode named(DomNode node, String name) {
        if (node != null && node.nodeType == 1 && name.equals(node.accessibleName)) return node;
        if (node == null) return null;
        for (DomNode child : node.children) {
            DomNode found = named(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private static DomNode element(DomNode node, String tag) {
        if (node != null && node.nodeType == 1 && tag.equals(node.localName) && !hasElementChild(node)) return node;
        if (node == null) return null;
        for (DomNode child : node.children) {
            DomNode found = element(child, tag);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean hasElementChild(DomNode node) {
        for (DomNode child : node.children) if (child.nodeType == 1) return true;
        return false;
    }
}
