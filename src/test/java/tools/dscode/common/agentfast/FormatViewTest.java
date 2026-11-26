package tools.dscode.common.agentfast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code --format=lines} is readable text. {@code --format=json} is objects. No browser. */
class FormatViewTest {
    @TempDir
    Path project;

    @Test
    void linesAreReadableAndJsonIsObjects() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role", "button");
        row.put("name", "Save");
        row.put("id", "s1.h1");
        row.put("ref", "ref:1:1");
        row.put("visible", true);
        row.put("enabled", true);
        row.put("interactive", true);
        row.put("frame", "top");

        Object lines = TargetVerb.viewLines(List.of(row), Map.of());
        List<?> lineList = assertInstanceOf(List.class, lines);
        assertInstanceOf(String.class, lineList.get(0));
        assertTrue(lineList.get(1).toString().startsWith("- button \"Save\""), lineList.toString());

        Object jsonRows = TargetVerb.viewLines(List.of(row), Map.of("format", "json"));
        List<?> objects = assertInstanceOf(List.class, jsonRows);
        Map<?, ?> object = assertInstanceOf(Map.class, objects.get(0));
        assertEquals("Save", object.get("name"));
        assertEquals("button", object.get("role"));
        assertFalse(objects.get(0) instanceof String);

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("ok", true);
        snap.put("elapsedMs", 1);
        snap.put("lines", objects);
        snap.put("sentence", "snap ok");
        Map<String, Object> wrapped = AgentEnvelope.apply(snap, 0, "snap", project, "fmt");
        assertInstanceOf(Map.class, ((List<?>) wrapped.get("items")).get(0));
        assertEquals("Save", ((Map<?, ?>) ((List<?>) wrapped.get("items")).get(0)).get("name"));

        String printed = AgentEnvelope.renderLines(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(wrapped));
        assertFalse(printed.contains("{\"role\""), printed);
        assertTrue(printed.contains("- button \"Save\""), printed);
        assertTrue(printed.contains("handle=s1.h1"), printed);

        Map<String, Object> find = new LinkedHashMap<>();
        find.put("ok", true);
        find.put("elapsedMs", 1);
        find.put("hits", List.of(row));
        find.put("sentence", "find ok");
        String findLines = AgentEnvelope.renderLines(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                AgentEnvelope.apply(find, 0, "find", project, "fmt")));
        assertFalse(findLines.contains("{\"role\""), findLines);
        assertTrue(findLines.contains("- button \"Save\""), findLines);

        Map<String, Object> poll = new LinkedHashMap<>();
        poll.put("ok", true);
        poll.put("elapsedMs", 1);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "click");
        event.put("source", "human");
        event.put("text", "Go");
        poll.put("events", List.of(event));
        poll.put("sentence", "ui-poll ok");
        String pollLines = AgentEnvelope.renderLines(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                AgentEnvelope.apply(poll, 0, "ui-poll", project, "fmt")));
        assertFalse(pollLines.contains("{\"type\""), pollLines);
        assertTrue(pollLines.contains("type=click"), pollLines);
        assertTrue(pollLines.contains("source=human"), pollLines);
        assertTrue(pollLines.contains("text=Go"), pollLines);
    }
}
