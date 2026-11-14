package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
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

class NegativeRelationTest {
    private static final String HTML = """
            <div id="anchor" data-x="100" data-y="100" data-w="40" data-h="20">Anchor</div>
            <div id="near" data-x="110" data-y="90" data-w="10" data-h="10">Near</div>
            <div id="far" data-x="0" data-y="0" data-w="10" data-h="10">Far</div>
            <div id="below" data-x="100" data-y="160" data-w="20" data-h="10">Below</div>
            <div id="above" data-x="100" data-y="40" data-w="10" data-h="10">Above</div>
            <div id="left" data-x="20" data-y="100" data-w="20" data-h="10">Left</div>
            <div id="right" data-x="180" data-y="100" data-w="20" data-h="10">Right</div>
            <button id="before">Before</button>
            <p id="mark">Mark</p>
            <button id="after">After</button>
            <button id="cancel">Cancel</button>
            <input id="name">
            <div id="dialog" data-x="300" data-y="300" data-w="80" data-h="80">Dialog</div>
            <button id="in" data-x="310" data-y="310" data-w="20" data-h="10">In</button>
            """;

    @TempDir
    Path temp;

    @AfterEach
    void reset() {
        DesktopTree.resetForTests();
        EngineLog.resetForTests();
    }

    @Test
    void everyRelationHasFourNegativeForms() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        assertComplement(root, "near", "css=#anchor", "div", "near", "far");
        assertComplement(root, "below", "css=#anchor", "div", "below", "far");
        assertComplement(root, "above", "css=#anchor", "div", "above", "below");
        assertComplement(root, "left-of", "css=#anchor", "div", "left", "right");
        assertComplement(root, "right-of", "css=#anchor", "div", "right", "left");
        assertComplement(root, "inside", "css=#dialog", "button", "in", "before");
        assertEquals(List.of("after"), ids(query(root, "role=button AND text=\"After\" AND after(css=#mark)")));
        assertEquals(List.of(), ids(query(root, "role=button AND text=\"After\" AND not-after(css=#mark)")));
        assertTrue(ids(query(root, "role=button AND not-after(css=#mark)")).contains("before"));
        assertFalse(ids(query(root, "role=button AND not-after(css=#mark)")).contains("after"));
        assertEquals(ids(query(root, "role=button AND not-after(css=#mark)")),
                ids(query(root, "role=button AND NOT after(css=#mark)")));
        assertEquals(ids(query(root, "css=#mark >> not-after=button")),
                ids(query(root, "css=#mark >> NOT after=button")));
        assertEquals(List.of("before"), ids(query(root, "role=button AND text=\"Before\" AND before(css=#mark)")));
        assertFalse(ids(query(root, "role=button AND not-before(css=#mark)")).contains("before"));
        assertTrue(ids(query(root, "role=button AND not-before(css=#mark)")).contains("after"));
    }

    @Test
    void notOfAGroupStaysAComplement() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        List<String> generic = ids(query(root, "NOT (near(css=#anchor) AND css=#far)"));
        assertTrue(generic.contains("near"), generic.toString());
        assertTrue(generic.contains("far"), generic.toString());
        assertFalse(ids(query(root, "css=div AND not-near(css=#anchor)")).contains("near"));
    }

    @Test
    void weightsAreParsedAndIgnoredByTheFilter() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        assertEquals(ids(query(root, "css=div AND near(css=#anchor)")),
                ids(query(root, "css=div AND near@4(css=#anchor)")));
        assertEquals(ids(query(root, "css=div AND not-below(css=#anchor)")),
                ids(query(root, "css=div AND not-below@0.5(css=#anchor)")));
        List<RelationTerm> terms = RelationTerm.parseAll("css=div AND near@4(css=#anchor)");
        assertEquals(4.0, terms.get(0).weight());
    }

    @Test
    void missingAnchorKeepsEveryCandidateWhenNegated() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        JsonNode json = query(root, Map.of("pipeline", "css=div AND not-near(css=#missing)", "explain", "true"));
        assertTrue(ids(json).contains("far"), ids(json).toString());
        assertTrue(ids(json).contains("near"));
        assertTrue(ids(json).contains("anchor"));
        boolean why = false;
        for (JsonNode stage : json.path("explain")) {
            if ("anchor matched 0".equals(stage.path("why").asText())) why = true;
        }
        assertTrue(why, json.path("explain").toString());
        assertEquals(List.of(), ids(query(root, "css=div AND near(css=#missing)")));
    }

    @Test
    void explainListsTheRelationTerms() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        JsonNode json = query(root, Map.of(
                "pipeline", "role=textbox AND not-near(text=\"Cancel\")",
                "explain", "true"));
        assertEquals(List.of("name"), ids(json));
        JsonNode term = json.path("relations").get(0);
        assertEquals("near", term.path("relation").asText());
        assertTrue(term.path("negated").asBoolean());
        assertEquals("text=\"Cancel\"", term.path("anchor").asText());
        assertEquals(1.0, term.path("weight").asDouble());
        JsonNode traced = query(root, Map.of("pipeline", "css=#anchor >> not-near=div", "explain", "true"));
        boolean engine = false;
        for (JsonNode stage : traced.path("explain")) {
            if ("not-near".equals(stage.path("engine").asText())) engine = true;
        }
        assertTrue(engine, traced.path("explain").toString());
        assertEquals(RelationTerm.parseAll("role=textbox AND not-near(text=\"Cancel\")").size(), json.path("relations").size());
    }

    @Test
    void desktopNegativesMatchTheBrowser() throws Exception {
        DomNode browser = HtmlTree.parse(HTML);
        Path fixture = temp.resolve("desk.json");
        Files.writeString(fixture, """
                {"role":"window","name":"W","x":0,"y":0,"width":500,"height":400,"children":[
                  {"role":"group","name":"Anchor","x":100,"y":100,"width":40,"height":20},
                  {"role":"group","name":"Near","x":110,"y":90,"width":10,"height":10},
                  {"role":"group","name":"Far","x":0,"y":0,"width":10,"height":10},
                  {"role":"button","name":"In","x":310,"y":310,"width":20,"height":10},
                  {"role":"group","name":"Dialog","x":300,"y":300,"width":80,"height":80}
                ]}
                """);
        DesktopTree.attach("d", fixture);
        assertTrue(names(DesktopTree.query("d", Map.of("pipeline", "role=group AND not-near(role=group[name=Anchor])"))).contains("Far"));
        assertFalse(names(DesktopTree.query("d", Map.of("pipeline", "role=group AND not-near(role=group[name=Anchor])"))).contains("Near"));
        assertEquals(List.of("In"), names(DesktopTree.query("d", Map.of("pipeline", "role=button AND inside(role=group[name=Dialog])"))));
        assertFalse(names(DesktopTree.query("d", Map.of("pipeline", "role=button AND not-inside(role=group[name=Dialog])"))).contains("In"));
        assertFalse(ids(query(browser, "css=#far AND not-near(css=#anchor)")).isEmpty());
    }

    @Test
    void relationLogIsWrittenOnce() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        Path dir = temp.resolve("logs");
        EngineLog.configure(Map.of("DOMENGINE_LOG", "relation", "DOMENGINE_LOG_DIR", dir.toString()));
        query(root, Map.of("pipeline", "css=div AND not-near(css=#anchor)"));
        String log = Files.readString(Files.list(dir).filter(path -> path.getFileName().toString().endsWith(".log")).findFirst().orElseThrow());
        assertEquals(1, count(log, "not-near anchors="), log);
    }

    private static void assertComplement(DomNode root, String relation, String anchor, String kind, String kept, String dropped) throws Exception {
        String positive = "css=" + kind + " AND " + relation + "(" + anchor + ")";
        String negative = "css=" + kind + " AND not-" + relation + "(" + anchor + ")";
        String notWord = "css=" + kind + " AND NOT " + relation + "(" + anchor + ")";
        String stage = anchor + " >> not-" + relation + "=" + kind;
        String notStage = anchor + " >> NOT " + relation + "=" + kind;
        List<String> pos = ids(query(root, positive));
        List<String> neg = ids(query(root, negative));
        assertTrue(pos.contains(kept), relation + " positive " + pos);
        assertFalse(neg.contains(kept), relation + " negative " + neg);
        assertTrue(neg.contains(dropped), relation + " negative dropped " + neg);
        assertEquals(neg, ids(query(root, notWord)), relation);
        assertEquals(ids(query(root, stage)), ids(query(root, notStage)), relation + " stage");
        assertFalse(ids(query(root, stage)).contains(kept), relation + " stage " + ids(query(root, stage)));
    }

    private static JsonNode query(DomNode root, String pipeline) throws Exception {
        return query(root, Map.of("pipeline", pipeline));
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

    private static List<String> names(JsonNode json) {
        List<String> names = new ArrayList<>();
        for (JsonNode node : json.path("composite")) {
            String name = node.path("name").asText("");
            if (!name.isEmpty()) names.add(name);
        }
        return names;
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
