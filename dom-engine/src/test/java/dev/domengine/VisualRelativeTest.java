package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualRelativeTest {
    private static final String HTML = """
            <div id="anchor" data-x="100" data-y="100" data-w="40" data-h="20"></div>
            <div id="up-near" data-x="100" data-y="70" data-w="10" data-h="10"></div>
            <div id="up-far" data-x="100" data-y="0" data-w="10" data-h="10"></div>
            <div id="down" data-x="100" data-y="160" data-w="20" data-h="10"></div>
            <div id="left" data-x="20" data-y="100" data-w="30" data-h="20"></div>
            <div id="right" data-x="180" data-y="100" data-w="30" data-h="20"></div>
            <div id="overlap" data-x="110" data-y="70" data-w="20" data-h="40"></div>
            <div id="host">
              <template shadowrootmode="open">
                <span id="inner" data-x="100" data-y="10" data-w="10" data-h="10"></span>
              </template>
            </div>
            """;

    @TempDir
    Path temp;

    @AfterEach
    void resetLog() {
        EngineLog.resetForTests();
    }

    @Test
    void aboveLeftAndRightUseTheLockedEdgesAndDistanceOrder() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        assertEquals(List.of("up-near", "up-far"), ids(query(root, "css=#anchor >> above=div")));
        assertEquals(List.of("left"), ids(query(root, "css=#anchor >> left-of=div")));
        assertEquals(List.of("right"), ids(query(root, "css=#anchor >> right-of=div")));
        assertEquals(List.of("right"), ids(query(root, "css=#anchor >> right=#right")));
        assertEquals(List.of("down"), ids(query(root, "css=#anchor >> below=#down")));
        assertFalse(ids(query(root, "css=#anchor >> above=*")).contains("inner"));
        assertFalse(ids(query(root, "css=#anchor >> above=div")).contains("overlap"));
        assertFalse(ids(query(root, "css=#anchor >> above=div")).contains("down"));
    }

    @Test
    void hiddenCandidatesAreSkippedAndNearBelowStayOnDocumentBoxes() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        DomNode hidden = find(root, "up-near");
        hidden.displayed = false;
        assertEquals(List.of("up-far"), ids(query(root, "css=#anchor >> above=div")));
        DomNode page = HtmlTree.parse(Files.readString(Path.of("src/test/resources/fixtures/page.html")));
        assertEquals("under", query(page, "css=#above >> below=#under").path("nodes").get(0).path("attributes").path("id").asText());
        assertEquals("under", query(page, "css=#above >> near=#under").path("nodes").get(0).path("attributes").path("id").asText());
    }

    @Test
    void humanExposureUsesTheVisualBoxWhenOneWasStored() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        DomNode anchor = find(root, "anchor");
        DomNode up = find(root, "up-near");
        anchor.hasVisual = true;
        anchor.visualX = anchor.x;
        anchor.visualY = anchor.y;
        up.hasVisual = true;
        up.visualX = up.x;
        up.visualY = 200;
        assertEquals(List.of("up-near", "up-far"), ids(query(root, "css=#anchor >> above=#up-near,#up-far")));
        JsonNode human = Jsons.MAPPER.readTree(Jsons.pretty(Query.run(root, Map.of(
                "pipeline", "css=#anchor >> above=#up-near",
                "exposure", "human"
        ))));
        assertEquals(0, human.path("nodes").size(), human.toString());
        assertTrue(human.path("source").asText().contains("above"));
    }

    @Test
    void cliReachesTheRelativesAndVisualLoggingStaysOffByDefault() throws Exception {
        Path file = temp.resolve("boxes.html");
        Files.writeString(file, HTML);
        CliResult result = DomEngineMain.dispatch(List.of(
                "query", "--file", file.toString(), "--pipeline", "css=#anchor >> left-of=#left", "--explain"));
        assertEquals(0, result.exit(), result.stderr());
        JsonNode json = Jsons.MAPPER.readTree(result.stdout());
        assertEquals("left", json.path("nodes").get(0).path("attributes").path("id").asText());
        assertEquals("left-of", json.path("explain").get(1).path("engine").asText());
        assertTrue(json.path("explain").get(1).path("why").asText().contains("document_css_px"));
        CliResult help = DomEngineMain.dispatch(List.of("query", "--help"));
        assertTrue(help.stdout().contains("left-of"), help.stdout());
        assertTrue(help.stdout().contains("above"), help.stdout());

        DomNode root = HtmlTree.parse(HTML);
        Path off = temp.resolve("off");
        EngineLog.configure(Map.of("DOMENGINE_LOG_DIR", off.toString()));
        query(root, "css=#anchor >> above=#up-near");
        if (Files.isDirectory(off)) assertEquals(0, Files.list(off).count());

        Path dir = temp.resolve("on");
        EngineLog.configure(Map.of("COMPOSITE_LOG", "visual", "COMPOSITE_LOG_DIR", dir.toString()));
        query(root, "css=#anchor >> above=#up-near");
        String text = Files.readString(Files.list(dir).filter(path -> path.getFileName().toString().endsWith(".log")).findFirst().orElseThrow());
        assertTrue(text.startsWith("20"), text);
        assertTrue(text.contains("visual id=visual/"), text);
        assertTrue(text.contains("document_css_px above ranked"), text);
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

    private static DomNode find(DomNode node, String id) {
        if (id.equals(node.attributes.get("id"))) return node;
        for (DomNode child : node.children) {
            DomNode found = find(child, id);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = find(shadow, id);
            if (found != null) return found;
        }
        return null;
    }
}
