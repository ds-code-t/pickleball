package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.query.RowStates;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** File-backed proof for filter, limit, and the row-state contract. Live Chrome covers the rest. */
class QueryFlagOfflineTest {
    @Test
    void filterLimitAndRowStatesMatchTheContract() throws Exception {
        Path page = Files.createTempFile("flags", ".html");
        Files.writeString(page, """
                <!DOCTYPE html><body>
                <button class="item" id="hid" style="display:none">H</button>
                <button class="item" id="a">A</button>
                <button class="item" id="b">B</button>
                <button class="item" id="c">C</button>
                <input id="off" disabled>
                <input id="box" type="checkbox" checked>
                <select><option id="opt" selected>B</option></select>
                <input id="rr" required readonly>
                <button id="open" aria-expanded="true">Open</button>
                <button id="shut" aria-expanded="false">Shut</button>
                <iframe id="f"></iframe>
                </body>
                """);
        JsonNode shown = query(page, "--css", ".item", "--filter", "displayed");
        assertEquals(List.of("a", "b", "c"), ids(shown));
        JsonNode second = query(page, "--css", ".item", "--filter", "displayed", "--nth", "1");
        assertEquals(List.of("b"), ids(second));
        JsonNode counted = query(page, "--css", ".item", "--filter", "displayed", "--count-only");
        assertEquals(3, counted.path("count").asInt());
        JsonNode limited = query(page, "--css", ".item", "--filter", "displayed", "--limit", "2", "--count-only");
        assertEquals(2, limited.path("count").asInt());
        assertTrue(states(query(page, "--css", "#hid"), "hid").contains("hidden"));
        assertTrue(states(query(page, "--css", "#off"), "off").contains("disabled"));
        assertTrue(states(query(page, "--css", "#box"), "box").contains("checked"));
        assertTrue(states(query(page, "--css", "#opt"), "opt").contains("selected"));
        List<String> rr = states(query(page, "--css", "#rr"), "rr");
        assertTrue(rr.contains("required"), rr.toString());
        assertTrue(rr.contains("readonly"), rr.toString());
        assertTrue(states(query(page, "--css", "#open"), "open").contains("expanded"));
        assertTrue(states(query(page, "--css", "#shut"), "shut").contains("collapsed"));
        CliResult missing = DomEngineMain.dispatch(List.of("query", "--file", page.toString(), "--frame", "missing", "--css", "#a"));
        assertEquals(1, missing.exit(), missing.stderr());
        assertEquals("not_found", Jsons.MAPPER.readTree(missing.stderr()).path("code").asText());
        JsonNode caps = Jsons.MAPPER.readTree(DomEngineMain.dispatch(List.of("capabilities")).stdout());
        List<String> listed = new ArrayList<>();
        caps.path("row_states").forEach(node -> listed.add(node.asText()));
        assertEquals(RowStates.NAMES, listed);
        assertEquals("256", caps.path("eval_nodes").asText());
        assertTrue(caps.path("actions").toString().contains("hover"));
    }

    private static JsonNode query(Path page, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("query");
        command.add("--file");
        command.add(page.toString());
        command.addAll(List.of(args));
        CliResult result = DomEngineMain.dispatch(command);
        assertEquals(0, result.exit(), result.stderr());
        return Jsons.MAPPER.readTree(result.stdout());
    }

    private static List<String> ids(JsonNode json) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : json.path("nodes")) ids.add(node.path("attributes").path("id").asText());
        return ids;
    }

    private static List<String> states(JsonNode json, String id) {
        for (JsonNode node : json.path("composite")) {
            if (id.equals(node.path("attributes").path("id").asText())) {
                List<String> states = new ArrayList<>();
                node.path("states").forEach(item -> states.add(item.asText()));
                return states;
            }
        }
        return List.of();
    }
}
