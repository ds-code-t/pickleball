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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookQueryTest {
    private static final String HTML = """
            <button id="go" style="color: red; opacity: 0.8; background-color: #fff">Go</button>
            <button id="dim" style="color: rgb(255, 0, 0); opacity: 0.2; background-color: #ffffff">Dim</button>
            <button id="blue" style="color: #00f; opacity: 0.9; background-color: white">Blue</button>
            <div id="gone" style="display: none; color: #f00"></div>
            <div id="card" style="background-color: #000">
              <span id="label" style="color: #fff">Hi</span>
            </div>
            <span id="pale" style="color: #eeeeee; background-color: #ffffff">Pale</span>
            <p id="big" style="font-size: 16px; font-weight: bold">Big</p>
            <p id="small" style="font-size: 10px; font-weight: 400">Small</p>
            <div id="host">
              <template shadowrootmode="open">
                <span id="inner" style="color: red">In</span>
              </template>
            </div>
            <div id="parent" style="color: red; background-color: #fff">
              <span id="child">Kid</span>
            </div>
            """;

    @TempDir
    Path temp;

    @AfterEach
    void resetLog() {
        EngineLog.resetForTests();
    }

    @Test
    void colorOpacityAndGroupsUseComputedStyleNotAnAttributeEngine() throws Exception {
        assertTrue(Query.ENGINES.contains("look"));
        assertFalse(Query.ENGINES.contains("attribute"));
        assertFalse(Query.ENGINES.contains("style"));
        DomNode root = HtmlTree.parse(HTML);
        assertEquals(List.of("go", "dim", "gone", "parent", "child"), ids(query(root, "look=color=red")));
        assertEquals(List.of("go", "blue"), ids(query(root, "look=opacity>0.5,background-color=#fff")));
        assertEquals(List.of("go", "blue"), ids(query(root, "look=opacity>0.5,background-color=#fff|color=blue")));
        assertEquals(List.of("go"), ids(query(root, "(css=#go AND look=color=red)")));
        assertEquals(List.of("go"), ids(query(root, "look=color=rgb(255, 0, 0),opacity>0.5")));
        assertEquals(List.of("gone"), ids(query(root, "look=display=none")));
        assertFalse(ids(query(root, "look=color=red")).contains("inner"));
    }

    @Test
    void colorInheritsAndAStoredComputedMapWins() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        assertEquals(List.of("child"), ids(query(root, "css=#parent >> look=color=red")));
        assertTrue(ids(query(root, "css=#parent >> look=background-color=#fff")).isEmpty());
        DomNode child = find(root, "child");
        child.computed.put("color", "blue");
        assertEquals(List.of("go", "dim", "gone", "parent"), ids(query(root, "look=color=red")));
        assertTrue(ids(query(root, "look=color=blue")).contains("child"));
        assertFalse(ids(query(root, "css=#parent >> look=background-color=#fff")).contains("child"));
    }

    @Test
    void contrastAndFontNumbersStayInsideTheLookStage() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        assertEquals(List.of("blue", "label"), ids(query(root, "look=contrast>4.5")));
        assertTrue(ids(query(root, "look=contrast>20")).contains("label"));
        assertFalse(ids(query(root, "look=contrast>4.5")).contains("pale"));
        assertEquals(List.of("big"), ids(query(root, "look=font-size>12")));
        assertEquals(List.of("big"), ids(query(root, "look=font-weight=700")));
        assertEquals(List.of("small"), ids(query(root, "look=font-weight<=400")));
        dev.domengine.cdp.CdpException failure = assertThrows(dev.domengine.cdp.CdpException.class,
                () -> query(root, "look="));
        assertTrue(failure.getMessage().contains("invalid look"));
    }

    @Test
    void cliReachesLookAndLoggingStaysOffByDefault() throws Exception {
        Path file = temp.resolve("look.html");
        Files.writeString(file, HTML);
        CliResult result = DomEngineMain.dispatch(List.of(
                "query", "--file", file.toString(), "--selector", "look=color=red", "--explain"));
        assertEquals(0, result.exit(), result.stderr());
        JsonNode json = Jsons.MAPPER.readTree(result.stdout());
        assertEquals("go", json.path("nodes").get(0).path("attributes").path("id").asText());
        assertEquals("look", json.path("explain").get(0).path("engine").asText());
        assertTrue(json.path("explain").get(0).path("why").asText().contains("look matched"), json.toString());
        CliResult piped = DomEngineMain.dispatch(List.of(
                "query", "--file", file.toString(), "--pipeline", "look=opacity>0.5,background-color=#fff"));
        assertEquals(0, piped.exit(), piped.stderr());
        assertEquals("go", Jsons.MAPPER.readTree(piped.stdout()).path("nodes").get(0).path("attributes").path("id").asText());
        CliResult help = DomEngineMain.dispatch(List.of("query", "--help"));
        assertTrue(help.stdout().contains("look=color=red"), help.stdout());
        CliResult caps = DomEngineMain.dispatch(List.of("capabilities"));
        assertTrue(caps.stdout().contains("\"look\""), caps.stdout());
        assertFalse(caps.stdout().contains("\"attribute\""), caps.stdout());

        DomNode root = HtmlTree.parse(HTML);
        Path off = temp.resolve("off");
        EngineLog.configure(Map.of("DOMENGINE_LOG_DIR", off.toString()));
        query(root, "look=color=red");
        if (Files.isDirectory(off)) assertEquals(0, Files.list(off).count());

        Path dir = temp.resolve("on");
        EngineLog.configure(Map.of("DOMENGINE_LOG", "look", "DOMENGINE_LOG_DIR", dir.toString()));
        query(root, "look=color=red");
        String text = Files.readString(Files.list(dir).filter(path -> path.getFileName().toString().endsWith(".log")).findFirst().orElseThrow());
        assertTrue(text.startsWith("20"), text);
        assertTrue(text.contains("look id=look/"), text);
        assertTrue(text.contains("color=#ff0000"), text);
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
