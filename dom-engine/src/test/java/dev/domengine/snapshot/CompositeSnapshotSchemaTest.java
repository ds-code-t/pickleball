package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeSnapshotSchemaTest {
    @Test
    void builderOutputValidatesAgainstTheSchemaFile() throws Exception {
        JsonNode schema = Jsons.MAPPER.readTree(CompositeSnapshot.class.getResourceAsStream(
                "/dev/domengine/snapshot/composite-snapshot.schema.json"));
        String html = Files.readString(Path.of("src/test/resources/fixtures/affinity-form.html"));
        JsonNode json = CompositeSnapshots.offline(HtmlTree.parse(html), SnapshotOptions.fromFlags(java.util.Map.of(
                "file", "src/test/resources/fixtures/affinity-form.html")));
        SnapshotSchemas.assertValid(schema, json);
        SnapshotSchemas.assertValid(schema, CompositeSnapshots.offline(new dev.domengine.tree.DomNode(), new SnapshotOptions()));
    }

    @Test
    void parserAcceptsV1AndV2() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/fixtures/affinity-form.html"));
        DomNode root = HtmlTree.parse(html);
        SnapshotOptions options = SnapshotOptions.fromFlags(java.util.Map.of(
                "file", "src/test/resources/fixtures/affinity-form.html"));
        JsonNode v1 = CompositeSnapshots.offline(root, options);
        assertEquals(1, v1.path("version").asInt());
        assertTrue(v1.path("canonical").isMissingNode());
        assertEquals("urn:pickleball:composite-snapshot:1", SnapshotDocuments.schema(1).path("$id").asText());
        SnapshotSchemas.assertValid(SnapshotDocuments.schema(1), v1);
        assertEquals(v1, SnapshotDocuments.read(v1).toJson());
        assertEquals(1, CompositeSnapshot.fromJson(v1).schemaVersion());

        CanonicalDocument canonical = CanonicalDocument.html(new CanonicalDocument.Frame(
                "main", "", "", "", 0, 0, html, true));
        JsonNode v2 = CompositeSnapshots.offline(root, options, canonical);
        assertEquals(2, v2.path("version").asInt());
        assertEquals("html", v2.path("canonical").path("kind").asText());
        assertEquals("preserved", v2.path("canonical").path("frames").get(0).path("shadow").asText());
        assertEquals(html, v2.path("canonical").path("frames").get(0).path("html").asText());
        assertEquals("urn:pickleball:composite-snapshot:2", SnapshotDocuments.schema(2).path("$id").asText());
        SnapshotSchemas.assertValid(SnapshotDocuments.schema(2), v2);
        assertEquals(v2, SnapshotDocuments.read(v2).toJson());
        assertEquals(2, CompositeSnapshot.fromJson(v2).schemaVersion());
        assertFalse(SnapshotSchemas.violations(SnapshotDocuments.schema(1), v2).isEmpty());
        assertFalse(SnapshotSchemas.violations(SnapshotDocuments.schema(2), v1).isEmpty());

        CanonicalDocument desktop = CanonicalDocument.desktop("<html><body><button>Go</button></body></html>", "<node role=\"button\"/>");
        JsonNode desktopJson = CompositeSnapshots.offline(root, options, desktop);
        assertEquals("html-ish", desktopJson.path("canonical").path("kind").asText());
        assertEquals("data-pkb-native-path", desktopJson.path("canonical").path("side").path("native_path_attr").asText());
        assertEquals("<node role=\"button\"/>", desktopJson.path("canonical").path("side").path("a11y_xml").asText());
        SnapshotSchemas.assertValid(SnapshotDocuments.schema(2), desktopJson);
        assertEquals(desktopJson, SnapshotDocuments.read(desktopJson).toJson());

        ObjectNode version3 = (ObjectNode) v1.deepCopy();
        version3.put("version", 3);
        IllegalArgumentException badVersion = assertThrows(IllegalArgumentException.class, () -> SnapshotDocuments.read(version3));
        assertTrue(badVersion.getMessage().contains("unsupported snapshot version 3"), badVersion.getMessage());

        ObjectNode orphan = (ObjectNode) v1.deepCopy();
        orphan.remove("version");
        orphan.set("canonical", canonical.toJson());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> CompositeSnapshot.fromJson(orphan));
        assertTrue(missing.getMessage().contains("canonical without version"), missing.getMessage());

        ObjectNode bare = (ObjectNode) v2.deepCopy();
        bare.remove("canonical");
        IllegalArgumentException noTree = assertThrows(IllegalArgumentException.class, () -> CompositeSnapshot.fromJson(bare));
        assertTrue(noTree.getMessage().contains("version 2 requires canonical"), noTree.getMessage());

        ObjectNode mixed = (ObjectNode) v1.deepCopy();
        mixed.set("canonical", canonical.toJson());
        IllegalArgumentException v1Canonical = assertThrows(IllegalArgumentException.class, () -> CompositeSnapshot.fromJson(mixed));
        assertTrue(v1Canonical.getMessage().contains("version 1 cannot include canonical"), v1Canonical.getMessage());
    }
}
