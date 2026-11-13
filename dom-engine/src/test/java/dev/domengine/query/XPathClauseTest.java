package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
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

class XPathClauseTest {
    private static final String HTML = """
            <div id="billing">
              <button id="save">Save</button>
              <span id="note">Note</span>
            </div>
            <button id="other">Other</button>
            """;

    @TempDir
    Path temp;

    @AfterEach
    void reset() {
        Query.resetXPathFallbackForTests();
        EngineLog.resetForTests();
    }

    @Test
    void xpathIsAClauseInsideBooleanAndPipeline() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        assertEquals(List.of("save"), ids(query(root, Map.of("pipeline", "xpath=//button[@id='save'] AND role=button"))));
        assertEquals(List.of("save", "note"), ids(query(root, Map.of("pipeline", "xpath=//button[@id='save'] OR xpath=//span"))));
        List<String> complement = ids(query(root, Map.of("pipeline", "NOT xpath=//button")));
        assertTrue(complement.contains("note"), complement.toString());
        assertFalse(complement.contains("save"), complement.toString());
        assertEquals(List.of("save"), ids(query(root, Map.of("pipeline", "css=#billing >> xpath=//button[@id='save']"))));
    }

    @Test
    void pipelineWinsUntilItMatchesNothing() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        Map<String, String> hit = new LinkedHashMap<>();
        hit.put("pipeline", "(role=button AND text=\"Save\")");
        hit.put("xpath", "//button[@id='save']");
        hit.put("explain", "true");
        JsonNode matched = query(root, hit);
        assertEquals(List.of("save"), ids(matched));
        assertFalse(matched.has("fallback"), matched.toString());
        assertFalse(matched.path("explain").toString().contains("fallback"), matched.toString());

        Map<String, String> miss = new LinkedHashMap<>();
        miss.put("pipeline", "role=button AND text=\"Nope\"");
        miss.put("xpath", "//button[@id='save']");
        miss.put("explain", "true");
        JsonNode fallen = query(root, miss);
        assertEquals("xpath", fallen.path("fallback").asText());
        assertEquals(List.of("save"), ids(fallen));
        JsonNode stage = fallen.path("explain").get(fallen.path("explain").size() - 1);
        assertEquals("xpath", stage.path("engine").asText());
        assertEquals("fallback: combined matched 0", stage.path("why").asText());
    }

    @Test
    void xpathFallbackWarnsOncePerExpression() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        Path dir = temp.resolve("logs");
        EngineLog.configure(Map.of("DOMENGINE_LOG", "query", "DOMENGINE_LOG_DIR", dir.toString()));
        Map<String, String> flags = Map.of("pipeline", "role=button AND text=\"Nope\"", "xpath", "//button[@id='save']");
        query(root, flags);
        query(root, flags);
        String log = Files.readString(Files.list(dir).filter(path -> path.getFileName().toString().endsWith(".log")).findFirst().orElseThrow());
        assertEquals(1, count(log, "xpath fallback //button[@id='save']"), log);
    }

    @Test
    void xpathOnlyBodyIsUnchanged() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        JsonNode json = query(root, Map.of("xpath", "//button[@id='save']"));
        assertEquals(List.of("save"), ids(json));
        assertFalse(json.has("fallback"));
        assertEquals("xpath=//button[@id='save']", json.path("source").asText());
    }

    private static JsonNode query(DomNode root, Map<String, String> flags) throws Exception {
        return Jsons.MAPPER.readTree(Jsons.pretty(Query.run(root, flags)));
    }

    private static List<String> ids(JsonNode json) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : json.path("nodes")) {
            String id = node.path("attributes").path("id").asText("");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    private static int count(String text, String needle) {
        int found = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            found++;
            at += needle.length();
        }
        return found;
    }
}
