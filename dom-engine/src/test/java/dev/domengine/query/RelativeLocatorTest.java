package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.json.Jsons;
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

class RelativeLocatorTest {
    private static final String BOXES = """
            <div id="anchor" data-x="100" data-y="100" data-w="40" data-h="20"></div>
            <div id="up-near" data-x="100" data-y="70" data-w="10" data-h="10"></div>
            <div id="up-far" data-x="100" data-y="0" data-w="10" data-h="10"></div>
            <div id="down" data-x="100" data-y="160" data-w="20" data-h="10"></div>
            <div id="left" data-x="20" data-y="100" data-w="30" data-h="20"></div>
            <div id="right" data-x="180" data-y="100" data-w="30" data-h="20"></div>
            <div id="overlap" data-x="110" data-y="70" data-w="20" data-h="40"></div>
            """;

    private static final String ORDER = """
            <button id="before">Save</button>
            <div id="billing" role="region">
              Billing
              <button id="nested">Save</button>
            </div>
            <button id="after">Save</button>
            <p id="later">Later</p>
            """;

    private static final String INSIDE = """
            <div id="dialog" data-x="0" data-y="0" data-w="200" data-h="200"></div>
            <button id="in" data-x="10" data-y="10" data-w="40" data-h="20">In</button>
            <button id="equal" data-x="0" data-y="0" data-w="200" data-h="200">Eq</button>
            <button id="overlap" data-x="180" data-y="180" data-w="40" data-h="40">Over</button>
            <button id="out" data-x="300" data-y="10" data-w="20" data-h="20">Out</button>
            <button id="hidden" data-x="20" data-y="20" data-w="10" data-h="10">Hid</button>
            """;

    @TempDir
    Path temp;

    @AfterEach
    void reset() {
        DesktopTree.resetForTests();
    }

    @Test
    void insideKeepsABoxThatIsFullyInsideAndSkipsTheRest() throws Exception {
        DomNode root = HtmlTree.parse(INSIDE);
        find(root, "hidden").displayed = false;
        assertEquals(List.of("in", "equal"), ids(query(root, "css=#dialog >> inside=button")));
        assertFalse(ids(query(root, "css=#dialog >> inside=button")).contains("overlap"));
        assertFalse(ids(query(root, "css=#dialog >> inside=button")).contains("out"));
        assertFalse(ids(query(root, "css=#dialog >> inside=button")).contains("hidden"));

        DomNode dialog = find(root, "dialog");
        DomNode inside = find(root, "in");
        dialog.hasVisual = true;
        dialog.visualX = dialog.x;
        dialog.visualY = dialog.y;
        inside.hasVisual = true;
        inside.visualX = 400;
        inside.visualY = 400;
        JsonNode human = query(root, Map.of("pipeline", "css=#dialog >> inside=#in", "exposure", "human"));
        assertEquals(List.of(), ids(human));
    }

    @Test
    void afterAndBeforeUseDocumentOrderNotDescendants() throws Exception {
        DomNode root = HtmlTree.parse(ORDER);
        assertEquals(List.of("after"), ids(query(root, "role=button AND text=\"Save\" AND after(role=region AND text=\"Billing\")")));
        assertEquals(List.of("before"), ids(query(root, "role=button AND text=\"Save\" AND before(role=region AND text=\"Billing\")")));
        assertFalse(ids(query(root, "css=#billing >> after=*")).contains("nested"));
        assertEquals(List.of(), ids(query(root, "role=button AND after(text=\"NoSuchLabel\")")));
    }

    @Test
    void filterFormCoversEveryRelation() throws Exception {
        DomNode boxes = HtmlTree.parse(BOXES);
        assertEquals(List.of("down"), ids(query(boxes, "css=div AND below(css=#anchor)")));
        assertEquals(List.of("up-near", "up-far"), ids(query(boxes, "css=div AND above(css=#anchor)")));
        assertEquals(List.of("left"), ids(query(boxes, "css=div AND left-of(css=#anchor)")));
        assertEquals(List.of("right"), ids(query(boxes, "css=div AND right(css=#anchor)")));
        assertTrue(ids(query(boxes, "css=div AND near(css=#anchor)")).contains("down"));
        DomNode inside = HtmlTree.parse(INSIDE);
        find(inside, "hidden").displayed = false;
        assertEquals(List.of("in", "equal"), ids(query(inside, "role=button AND inside(css=#dialog)")));
        DomNode order = HtmlTree.parse(ORDER);
        assertEquals(List.of("after"), ids(query(order, "(role=button AND text=\"Save\") AND after(role=region AND text=\"Billing\")")));
        assertEquals(List.of("before"), ids(query(order, "(role=button AND text=\"Save\") AND before(css=#billing)")));
    }

    @Test
    void existingRelativesStayOnTheSameEdges() throws Exception {
        DomNode root = HtmlTree.parse(BOXES);
        assertEquals(List.of("up-near", "up-far"), ids(query(root, "css=#anchor >> above=div")));
        assertEquals(List.of("left"), ids(query(root, "css=#anchor >> left-of=div")));
        assertEquals(List.of("right"), ids(query(root, "css=#anchor >> right-of=div")));
        assertEquals(List.of("right"), ids(query(root, "css=#anchor >> right=#right")));
        assertEquals(List.of("down"), ids(query(root, "css=#anchor >> below=#down")));
        assertFalse(ids(query(root, "css=#anchor >> above=div")).contains("overlap"));
    }

    @Test
    void desktopTreeUsesTheSameRelations() throws Exception {
        Path fixture = temp.resolve("desk.json");
        Files.writeString(fixture, """
                {"role":"window","name":"W","x":0,"y":0,"width":400,"height":300,"children":[
                  {"role":"button","name":"Before","x":10,"y":10,"width":40,"height":16},
                  {"role":"region","name":"Billing","x":0,"y":40,"width":120,"height":80,"children":[
                    {"role":"button","name":"Inside","x":10,"y":50,"width":30,"height":16}
                  ]},
                  {"role":"button","name":"After","x":10,"y":140,"width":40,"height":16},
                  {"role":"label","name":"Near","x":20,"y":20,"width":10,"height":10}
                ]}
                """);
        DesktopTree.attach("d", fixture);
        assertEquals(List.of("Inside"), names(DesktopTree.query("d", Map.of("pipeline", "role=button AND inside(role=region)"))));
        assertEquals(List.of("After"), names(DesktopTree.query("d", Map.of("pipeline", "role=button AND after(role=region)"))));
        assertEquals(List.of("Before"), names(DesktopTree.query("d", Map.of("pipeline", "role=button AND before(role=region)"))));
        assertTrue(names(DesktopTree.query("d", Map.of("pipeline", "role=label AND near(role=button)"))).contains("Near"));
    }

    @Test
    void capabilitiesListTheNewEngines() throws Exception {
        assertTrue(Query.ENGINES.containsAll(List.of("inside", "after", "before")));
        CliResult result = DomEngineMain.dispatch(List.of("capabilities"));
        JsonNode json = Jsons.MAPPER.readTree(result.stdout());
        List<String> engines = new ArrayList<>();
        json.path("engines").forEach(node -> engines.add(node.asText()));
        assertTrue(engines.containsAll(List.of("inside", "after", "before")), engines.toString());
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

    private static DomNode find(DomNode node, String id) {
        if (id.equals(node.attributes.get("id"))) return node;
        for (DomNode child : node.children) {
            DomNode found = find(child, id);
            if (found != null) return found;
        }
        return null;
    }
}
