package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.tree.DomNode;
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

class BooleanConfirmTest {
    private static final String HTML = """
            <button id="submit">Submit</button>
            <span id="span">Submit</span>
            <button id="other">Other</button>
            <div id="panel"><button id="nested">Submit</button></div>
            """;

    @TempDir
    Path temp;

    @Test
    void precedenceAndGroupsStayStable() throws Exception {
        DomNode root = HtmlTree.parse(HTML);
        assertEquals("css=button AND text=\"Submit\"", Expressions.render(Expressions.parse("css=button AND text=\"Submit\"")));
        CdpException mixed = assertThrows(CdpException.class,
                () -> Expressions.parse("css=button AND text=\"Submit\" OR css=span"));
        assertTrue(mixed.getMessage().contains("parentheses"), mixed.getMessage());
        assertEquals(List.of("nested", "span", "submit"), ids(query(root, "(css=button OR css=span) AND text=\"Submit\"")).stream().sorted().toList());
        assertEquals(List.of("nested"), ids(query(root, "css=#panel >> (css=button AND text=\"Submit\")")));
        List<String> notAnd = ids(query(root, "NOT css=#submit AND css=button"));
        assertTrue(notAnd.contains("other"), notAnd.toString());
        assertTrue(!notAnd.contains("submit"));
        assertEquals("text=\"AND OR NOT\"", Expressions.render(Expressions.parse("text=\"AND OR NOT\"")));
        assertEquals(List.of(), ids(query(root, "text=\"AND OR NOT\"")));
        Expressions.parse("css=div:has(span)");
        assertTrue(ids(query(root, "role=button AND after(css=#submit)")).contains("other"));
    }

    @Test
    void lowercaseOperatorsRenderAsCapitalsAndWarnOnce() throws Exception {
        Expressions.resetOperatorWarningsForTests();
        Path dir = temp.resolve("logs");
        EngineLog.configure(Map.of("DOMENGINE_LOG", "expr", "DOMENGINE_LOG_DIR", dir.toString()));
        assertEquals("css=button AND text=\"Submit\"", Expressions.render(Expressions.parse("css=button and text=\"Submit\"")));
        assertEquals("css=button OR css=span", Expressions.render(Expressions.parse("css=button or css=span")));
        assertEquals("NOT css=button", Expressions.render(Expressions.parse("not css=button")));
        Expressions.parse("css=button and css=span");
        String log = Files.readString(Files.list(dir).filter(path -> path.toString().endsWith(".log")).findFirst().orElseThrow());
        assertEquals(1, count(log, "lowercase operator 'and' read as AND"), log);
        assertEquals(1, count(log, "lowercase operator 'or' read as OR"), log);
        assertEquals(1, count(log, "lowercase operator 'not' read as NOT"), log);
        EngineLog.resetForTests();
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
