package dev.domengine.premap;

import dev.domengine.query.HtmlTree;
import dev.domengine.snapshot.CompositeSnapshot;
import dev.domengine.snapshot.CompositeSnapshots;
import dev.domengine.snapshot.SnapshotOptions;
import dev.domengine.snapshot.SnapshotSchemas;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreMapTest {
    @Test
    void indexesColoursAndViewportAreFilled() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/fixtures/affinity-form.html"));
        DomNode root = HtmlTree.parse(html);
        var json = CompositeSnapshots.offline(root, new SnapshotOptions()).path("premap");
        assertFalse(json.path("indexes").path("by_role").isEmpty());
        assertFalse(json.path("indexes").path("by_tag").isEmpty());
        assertFalse(json.path("indexes").path("by_xpath").isEmpty());
        assertFalse(json.path("indexes").path("interactables").isEmpty());
        assertFalse(json.path("colours").isEmpty());
        assertTrue(json.path("viewport_map").size() > 0);
        assertTrue(json.path("image").has("grid"));
        var colour = json.path("colours").fields().next().getValue();
        assertFalse(colour.path("fg").asText().isBlank());
        assertFalse(colour.path("bg").asText().isBlank());
        assertTrue(colour.path("contrast").asDouble() > 1);
    }

    @Test
    void offlineSnapshotStillValidates() throws Exception {
        String html = """
                <html><body style="background-color:#ffffff">
                  <main><h1 style="color:#111111">Inbox</h1>
                    <button id="go" class="primary">Go</button>
                  </main>
                </body></html>
                """;
        DomNode root = HtmlTree.parse(html);
        var json = CompositeSnapshots.offline(root, new SnapshotOptions());
        var schema = CompositeSnapshot.class.getResourceAsStream("/dev/domengine/snapshot/composite-snapshot.schema.json");
        SnapshotSchemas.assertValid(new com.fasterxml.jackson.databind.ObjectMapper().readTree(schema), json);
        assertEquals("button", json.path("premap").path("indexes").path("by_role").path("button").get(0).asText().isBlank() ? "" : "button");
        assertTrue(json.path("premap").path("indexes").path("by_id").has("go"));
        assertTrue(json.path("premap").path("indexes").path("by_class").has("primary"));
    }
}
