package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
import dev.domengine.tree.ContextWalker;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectorParityTest {
    private final Path page = Path.of("src/test/resources/fixtures/page.html");

    @Test
    void offlineSelectorsMatchTheFixture() throws Exception {
        DomNode root = HtmlTree.parse(java.nio.file.Files.readString(page));
        assertEquals("button", id(query(root, Map.of("css", "#go"))));
        assertEquals("button", id(query(root, Map.of("xpath", "//button[@id='go']"))));
        assertEquals("button", id(query(root, Map.of("pipeline", "text=Save", "nesting", "deepest"))));
        assertEquals("button", id(query(root, Map.of("pipeline", "role=button[name=\"Save\"]"))));
        assertEquals("button", id(query(root, Map.of("pipeline", "a11y=role=button,name~=Save"))));
        assertEquals("button", id(query(root, Map.of("pipeline", "desc={\"tag\":\"button\",\"attr\":{\"id\":\"go\"}}"))));
        assertEquals("span", id(query(root, Map.of("pipeline", "css=#host >> shadow=* >> css=#inner"))));
        assertEquals("p", id(query(root, Map.of("pipeline", "frame=#child >> css=#framed"))));
        assertEquals("div", id(query(root, Map.of("pipeline", "css=#above >> below=#under"))));
        assertEquals("div", id(query(root, Map.of("pipeline", "css=#above >> near=#under"))));

        JsonNode geometry = query(root, Map.of("pipeline", "frame=#child >> css=#framed", "geometry", "true"));
        JsonNode box = geometry.path("nodes").get(0).path("geometry");
        assertEquals(5, box.path("frame_document_relative").path("x").asDouble(), 0.01);
        assertEquals(6, box.path("frame_document_relative").path("y").asDouble(), 0.01);
        assertEquals(106, box.path("document_css_px").path("y").asDouble(), 0.01, box.toString());
        assertEquals(5, box.path("document_css_px").path("x").asDouble(), 0.01);

        JsonNode point = query(root, Map.of("css", "*", "point", "15,25"));
        assertEquals("go", point.path("nodes").get(0).path("attributes").path("id").asText());

        JsonNode counted = query(root, Map.of("css", ".item", "count-only", "true"));
        assertEquals(2, counted.path("count").asInt());
        assertEquals(0, counted.path("nodes").size());

        JsonNode last = query(root, Map.of("css", ".item", "nth", "-1"));
        assertEquals("two", last.path("nodes").get(0).path("attributes").path("id").asText());

        JsonNode closed = query(root, Map.of("xpath", "//span[@id='inner']"));
        assertEquals(0, closed.path("nodes").size());
        JsonNode opened = query(root, Map.of("xpath", "//span[@id='inner']", "pierce", "open"));
        assertEquals("inner", opened.path("nodes").get(0).path("attributes").path("id").asText());

        List<DomNode> pierced = ContextWalker.list(root, ContextWalker.Order.PIERCE);
        assertTrue(pierced.stream().anyMatch(node -> "inner".equals(node.attributes.get("id"))));
        List<DomNode> document = ContextWalker.list(root, ContextWalker.Order.DOCUMENT);
        int host = indexOf(document, "host");
        int inner = indexOf(document, "inner");
        assertTrue(host >= 0 && inner > host, "document order lists the host before its shadow");
    }

    @Test
    void cliQueryExplainsThePipeline() throws Exception {
        CliResult result = DomEngineMain.dispatch(List.of(
                "query", "--file", page.toString(),
                "--pipeline", "css=#go",
                "--explain"
        ));
        assertEquals(0, result.exit(), result.stderr());
        JsonNode json = Jsons.MAPPER.readTree(result.stdout());
        assertEquals("go", json.path("composite").get(0).path("attributes").path("id").asText());
        assertEquals("css", json.path("explain").get(0).path("engine").asText());
        assertTrue(json.path("composite").get(0).path("source").asText().equals("Dom"));
    }

    private static JsonNode query(DomNode root, Map<String, String> flags) throws Exception {
        return Jsons.MAPPER.readTree(Jsons.pretty(Query.run(root, flags)));
    }

    private static String id(JsonNode json) {
        JsonNode node = json.path("nodes").get(0);
        String tag = node.path("node_name").asText("");
        return tag.toLowerCase();
    }

    private static int indexOf(List<DomNode> nodes, String id) {
        for (int i = 0; i < nodes.size(); i++) {
            if (id.equals(nodes.get(i).attributes.get("id"))) return i;
        }
        return -1;
    }
}
