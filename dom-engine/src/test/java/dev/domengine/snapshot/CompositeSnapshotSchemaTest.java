package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
