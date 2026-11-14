package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cdp.CdpException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossPlatformQueryTest {
    @TempDir
    Path temp;

    @AfterEach
    void reset() {
        DesktopTree.resetForTests();
    }

    @Test
    void theSameStringsSelectTheSameRolesOnBrowserAndDesktop() throws Exception {
        DomNode browser = HtmlTree.parse("""
                <button id="save" data-x="10" data-y="10" data-w="40" data-h="16">Save</button>
                <button id="cancel" data-x="200" data-y="10" data-w="40" data-h="16">Cancel</button>
                <input id="name" data-x="10" data-y="40" data-w="80" data-h="16">
                """);
        Path fixture = temp.resolve("desk.json");
        Files.writeString(fixture, """
                {"role":"window","name":"W","x":0,"y":0,"width":400,"height":200,"children":[
                  {"role":"button","name":"Save","x":10,"y":10,"width":40,"height":16},
                  {"role":"button","name":"Cancel","x":200,"y":10,"width":40,"height":16},
                  {"role":"textbox","name":"Name","x":10,"y":40,"width":80,"height":16}
                ]}
                """);
        DesktopTree.attach("desk", fixture);
        List<String> queries = List.of(
                "role=button",
                "role=button AND role=button",
                "role=button OR role=textbox",
                "(role=button OR role=textbox) AND role=button",
                "role=button AND not-near(role=textbox)",
                "role=button AND near(role=textbox)"
        );
        for (String pipeline : queries) {
            assertEquals(roles(Query.run(browser, Map.of("pipeline", pipeline))),
                    roles(DesktopTree.query("desk", Map.of("pipeline", pipeline))), pipeline);
        }
        JsonNode negated = Query.run(browser, Map.of("pipeline", "NOT role=textbox"));
        JsonNode desktopNegated = DesktopTree.query("desk", Map.of("pipeline", "NOT role=textbox"));
        assertTrue(!roles(negated).contains("textbox"));
        assertTrue(!roles(desktopNegated).contains("textbox"));
        assertTrue(roles(negated).contains("button"));
        assertTrue(roles(desktopNegated).contains("button"));
        JsonNode nth = Query.run(browser, Map.of("pipeline", "role=button", "nth", "0"));
        JsonNode desktopNth = DesktopTree.query("desk", Map.of("pipeline", "role=button", "nth", "0"));
        assertEquals(1, nth.path("nodes").size());
        assertEquals(roles(nth), roles(desktopNth));
        assertEquals(2, Query.run(browser, Map.of("pipeline", "role=button", "count-only", "true")).path("count").asInt());
        assertEquals(2, DesktopTree.query("desk", Map.of("pipeline", "role=button", "count-only", "true")).path("count").asInt());
        CdpException browserOnly = assertThrows(CdpException.class,
                () -> DesktopTree.query("desk", Map.of("pipeline", "role=button", "pierce", "open")));
        assertTrue(browserOnly.getMessage().contains("browser-only"), browserOnly.getMessage());
    }

    private static List<String> roles(JsonNode json) {
        List<String> roles = new ArrayList<>();
        for (JsonNode node : json.path("composite")) roles.add(node.path("role").asText(""));
        roles.sort(String::compareTo);
        return roles;
    }
}
