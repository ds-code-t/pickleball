package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.Query;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BooleanQueryTest {
    private static final String HTML = """
            <div id="panel">
              <button id="a">Submit</button>
              <span id="c">Submit</span>
            </div>
            <button id="b">Cancel</button>
            <div id="d">Other</div>
            """;

    @TempDir
    Path temp;

    @AfterEach
    void resetLog() {
        EngineLog.resetForTests();
    }

    @Test
    void andIntersectsOrUnionsAndNotComplements() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        assertEquals(List.of("a"), ids(query(root, "css=button AND text=\"Submit\"")));
        assertEquals(List.of("a", "b", "c"), ids(query(root, "css=button OR text=\"Submit\"")));
        List<String> complement = ids(query(root, "NOT css=button"));
        assertTrue(complement.contains("c"), complement.toString());
        assertTrue(complement.contains("d"), complement.toString());
        assertFalse(complement.contains("a"), complement.toString());
        assertFalse(complement.contains("b"), complement.toString());
        assertEquals(List.of("c"), ids(query(root, "css=#panel >> NOT css=button")));
    }

    @Test
    void parenthesesGroupMixedOperatorsAndKeepThePipeline() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        assertEquals(List.of("a", "c"), ids(query(root, "(css=button OR css=span) AND text=\"Submit\"")));
        assertEquals(List.of("a", "b", "c"), ids(query(root, "css=button OR (css=span AND text=\"Submit\")")));
        assertEquals(List.of("a"), ids(query(root, "css=#panel >> (css=button AND text=\"Submit\")")));
        CdpException mixed = assertThrows(CdpException.class,
                () -> query(root, "css=button AND text=\"Submit\" OR css=span"));
        assertTrue(mixed.getMessage().contains("parentheses"), mixed.getMessage());
        assertEquals("expr", Query.leadingEngine("(css=button AND text=\"Submit\") >> near=50"));
        assertEquals("css", Query.leadingEngine("css=#go"));
        assertEquals(List.of("expr", "near"), engines(Query.parsePipeline("(css=button AND text=\"Submit\") >> near=50")));
    }

    @Test
    void quotedTextDoesNotBecomeAnOperator() throws Exception {
        DomNode root = HtmlTree.parse("<p id=\"p\">AND OR NOT</p><button id=\"a\">Submit</button>");
        assertEquals(List.of("p"), ids(query(root, "text=\"AND OR NOT\"")));
        assertEquals(List.of("a"), ids(query(root, "css=button AND text=\"Submit\"")));
    }

    @Test
    void existingSequentialPipelineStaysTheSame() throws Exception {
        DomNode root = HtmlTree.parse(Files.readString(Path.of("src/test/resources/fixtures/page.html")));
        JsonNode below = query(root, "css=#above >> below=#under");
        assertEquals("under", below.path("nodes").get(0).path("attributes").path("id").asText());
        JsonNode near = query(root, "css=#above >> near=#under");
        assertEquals("under", near.path("nodes").get(0).path("attributes").path("id").asText());
    }

    @Test
    void cliSelectorAndPipelineAcceptBooleanExpressions() throws Exception {
        Path file = temp.resolve("page.html");
        Files.writeString(file, HTML);
        String selector = "(css=button AND text=\"Submit\")";
        CliResult bySelector = DomEngineMain.dispatch(List.of(
                "query", "--file", file.toString(), "--selector", selector, "--explain"));
        assertEquals(0, bySelector.exit(), bySelector.stderr());
        JsonNode selected = Jsons.MAPPER.readTree(bySelector.stdout());
        assertEquals("a", selected.path("nodes").get(0).path("attributes").path("id").asText());
        assertEquals("expr", selected.path("explain").get(0).path("engine").asText());

        CliResult byPipeline = DomEngineMain.dispatch(List.of(
                "query", "--file", file.toString(), "--pipeline", "css=#panel >> (css=button OR css=span)"));
        assertEquals(0, byPipeline.exit(), byPipeline.stderr());
        JsonNode piped = Jsons.MAPPER.readTree(byPipeline.stdout());
        assertEquals(List.of("a", "c"), ids(piped));

        CliResult help = DomEngineMain.dispatch(List.of("help"));
        assertTrue(help.stdout().contains("AND"), help.stdout());
        assertTrue(help.stdout().contains("--selector"), help.stdout());
    }

    @Test
    void expressionLoggingIsOffUntilTheExprEntityIsNamed() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        Path off = temp.resolve("off");
        EngineLog.configure(Map.of("DOMENGINE_LOG_DIR", off.toString()));
        query(root, "css=button AND text=\"Submit\"");
        if (Files.isDirectory(off)) assertEquals(0, Files.list(off).count());

        Path dir = temp.resolve("on");
        EngineLog.configure(Map.of(
                "DOMENGINE_LOG", "expr",
                "DOMENGINE_LOG_DIR", dir.toString()
        ));
        query(root, "css=button OR text=\"Submit\"");
        query(root, "NOT css=button");
        String text = Files.readString(Files.list(dir).filter(path -> path.getFileName().toString().endsWith(".log")).findFirst().orElseThrow());
        assertTrue(text.startsWith("20"), text);
        assertTrue(text.contains("expr id=expr/"), text);
        assertTrue(text.contains("or left="), text);
        assertTrue(text.contains("not universe="), text);
        assertFalse(text.contains(" query "), text);
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

    private static List<String> engines(List<Query.Parsed> stages) {
        List<String> names = new ArrayList<>();
        for (Query.Parsed stage : stages) names.add(stage.engine());
        return names;
    }
}
