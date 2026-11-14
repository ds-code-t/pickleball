package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.premap.OneMatchException;
import dev.domengine.premap.PreMapLookup;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookCallSiteTest {
    private static final String HTML = """
            <div id="panel">
              <button id="a">Submit</button>
              <span id="c">Submit</span>
            </div>
            <button id="b">Cancel</button>
            <div id="d">Other</div>
            """;
    private static final String VISUAL = """
            <div id="anchor" data-x="100" data-y="100" data-w="40" data-h="20"></div>
            <div id="up-near" data-x="100" data-y="70" data-w="10" data-h="10"></div>
            <div id="up-far" data-x="100" data-y="0" data-w="10" data-h="10"></div>
            <div id="down" data-x="100" data-y="160" data-w="20" data-h="10"></div>
            <div id="left" data-x="20" data-y="100" data-w="30" data-h="20"></div>
            <div id="right" data-x="180" data-y="100" data-w="30" data-h="20"></div>
            """;

    @TempDir
    Path temp;

    @AfterEach
    void reset() {
        EngineLog.resetForTests();
        PreMapLookup.resetForTests();
    }

    @Test
    void eachHookIsCalledFromExactlyOneSite() throws Exception {
        Path dir = Path.of("dom-engine/src/main/java/dev/domengine/query");
        if (!Files.isDirectory(dir)) dir = Path.of("src/main/java/dev/domengine/query");
        String query = Files.readString(dir.resolve("Query.java"));
        String expressions = Files.readString(dir.resolve("Expressions.java"));
        assertEquals(1, count(query, "QueryHooks.premap("));
        assertEquals(1, count(query, "QueryHooks.rank("));
        assertEquals(1, count(query, "QueryHooks.one("));
        assertEquals(1, count(expressions, "QueryHooks.candidates("));
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if ("QueryHooks.java".equals(name)) continue;
                String source = Files.readString(file);
                assertFalse(source.contains("dev.domengine.premap"), name);
                assertFalse(source.contains("dev.domengine.affinity"), name);
            }
        }
    }

    @Test
    void passThroughLeavesBooleanAndVisualHandlesUnchanged() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        assertEquals(List.of("a"), ids(query(root, "css=button AND text=\"Submit\"")));
        assertEquals(List.of("a", "b", "c"), ids(query(root, "css=button OR text=\"Submit\"")));
        List<String> complement = ids(query(root, "NOT css=button"));
        assertTrue(complement.contains("c"), complement.toString());
        assertTrue(complement.contains("d"), complement.toString());
        assertFalse(complement.contains("a"), complement.toString());
        assertFalse(complement.contains("b"), complement.toString());
        assertEquals(List.of("c"), ids(query(root, "css=#panel >> NOT css=button")));
        assertEquals(List.of("a", "c"), ids(query(root, "(css=button OR css=span) AND text=\"Submit\"")));
        assertEquals(List.of("a", "b", "c"), ids(query(root, "css=button OR (css=span AND text=\"Submit\")")));
        assertEquals(List.of("a"), ids(query(root, "css=#panel >> (css=button AND text=\"Submit\")")));
        DomNode words = HtmlTree.parse("<p id=\"p\">AND OR NOT</p><button id=\"a\">Submit</button>");
        assertEquals(List.of("p"), ids(query(words, "text=\"AND OR NOT\"")));

        DomNode visual = HtmlTree.parse(VISUAL);
        assertEquals(List.of("up-near", "up-far"), ids(query(visual, "css=#anchor >> above=div")));
        assertEquals(List.of("left"), ids(query(visual, "css=#anchor >> left-of=div")));
        assertEquals(List.of("right"), ids(query(visual, "css=#anchor >> right-of=div")));
        assertEquals(List.of("down"), ids(query(visual, "css=#anchor >> below=#down")));
        Path page = Path.of("src/test/resources/fixtures/page.html");
        if (!Files.isRegularFile(page)) page = Path.of("dom-engine/src/test/resources/fixtures/page.html");
        DomNode fixture = HtmlTree.parse(Files.readString(page));
        assertEquals("under", query(fixture, "css=#above >> below=#under").path("nodes").get(0).path("attributes").path("id").asText());
        assertEquals("under", query(fixture, "css=#above >> near=#under").path("nodes").get(0).path("attributes").path("id").asText());
    }

    @Test
    void strictReportsAmbiguousCandidatesAndNotFound() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        OneMatchException ambiguous = assertThrows(OneMatchException.class,
                () -> Query.run(root, Map.of("pipeline", "role=button", "strict", "true")));
        assertTrue(ambiguous.getMessage().startsWith("ambiguous: 2 candidates"), ambiguous.getMessage());
        assertEquals(2, ambiguous.candidates().size());
        OneMatchException missing = assertThrows(OneMatchException.class,
                () -> Query.run(root, Map.of("pipeline", "css=#missing", "strict", "true")));
        assertEquals("not found", missing.getMessage());

        Path file = temp.resolve("two.html");
        Files.writeString(file, HTML);
        CliResult two = DomEngineMain.dispatch(List.of(
                "query", "--file", file.toString(), "--pipeline", "role=button", "--strict"));
        assertEquals(1, two.exit(), two.stderr());
        JsonNode error = Jsons.MAPPER.readTree(two.stderr());
        assertTrue(error.path("error").asText().startsWith("ambiguous: 2 candidates"), error.toString());
        assertEquals("ambiguous", error.path("code").asText());
        assertEquals(2, error.path("candidates").size());
        assertFalse(error.path("ok").asBoolean());

        CliResult none = DomEngineMain.dispatch(List.of(
                "query", "--file", file.toString(), "--pipeline", "css=#missing", "--strict"));
        JsonNode notFound = Jsons.MAPPER.readTree(none.stderr());
        assertEquals("not found", notFound.path("error").asText());
        assertEquals("not_found", notFound.path("code").asText());
        assertTrue(notFound.path("candidates").isArray());
    }

    @Test
    void installedLookupIsTheLiveSession() {
        assertNotSame(PreMapLookup.PASS_THROUGH, PreMapLookup.installed());
        assertTrue(PreMapLookup.installed().passThrough());
        assertTrue(PreMapLookup.installed().current("session", "1").isEmpty());
        PreMapLookup.store("session", null);
        assertTrue(PreMapLookup.installed().current("session", "1").isEmpty());
    }

    @Test
    void queryLogNamesTheThreeHooks() throws Exception {
        Path dir = temp.resolve("logs");
        EngineLog.configure(Map.of("DOMENGINE_LOG", "query", "DOMENGINE_LOG_DIR", dir.toString()));
        DomNode root = HtmlTree.parse(HTML);
        JsonNode one = query(root, "css=#a");
        assertEquals("a", one.path("nodes").get(0).path("attributes").path("id").asText());
        String text = Files.readString(Files.list(dir).filter(path -> path.getFileName().toString().endsWith(".log")).findFirst().orElseThrow());
        assertTrue(text.contains("premap=hit"), text);
        assertTrue(text.contains("rank=distance"), text);
        assertTrue(text.contains("one=ok"), text);
        assertThrows(CdpException.class, () -> Query.run(root, Map.of()));
    }

    @Test
    void affinityExplainNamesTheMode() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        JsonNode ranked = Jsons.MAPPER.readTree(Jsons.pretty(Query.run(root, Map.of(
                "pipeline", "role=button",
                "rank", "affinity",
                "explain", "true"))));
        assertEquals("affinity", ranked.path("rank").path("mode").asText());
        assertEquals(2, ranked.path("rank").path("scores").size());
        JsonNode plain = Jsons.MAPPER.readTree(Jsons.pretty(Query.run(root, Map.of(
                "pipeline", "role=button",
                "explain", "true"))));
        assertEquals("distance", plain.path("rank").asText());
    }

    private static JsonNode query(DomNode root, String pipeline) throws Exception {
        return Jsons.MAPPER.readTree(Jsons.pretty(Query.run(root, Map.of("pipeline", pipeline))));
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
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) return count;
            count++;
            from = at + needle.length();
        }
    }
}
