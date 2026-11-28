package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner decision 2026-11-28 (HCR-25): each human-lock override is one line in the session event log. */
class HumanLockOverrideLogTest {
    @TempDir
    Path project;

    @Test
    void everyOverrideWritesWhoVerbAndTime() throws Exception {
        HumanLock.lock(project, "r1", "agent-a");
        Map<String, String> args = new LinkedHashMap<>();
        args.put("human-override", "fix the form");
        assertNull(HumanLock.check(project, "r1", "eval", args, "agent-a"));
        assertNull(HumanLock.check(project, "r1", "upload", new LinkedHashMap<>(Map.of("human-override", "attach")), "agent-a"));

        Path log = HumanLock.eventLog(project, "r1");
        List<String> lines = Files.readAllLines(log);
        assertEquals(2, lines.size(), lines.toString());
        JsonNode first = new ObjectMapper().readTree(lines.get(0));
        assertEquals("human-lock-override", first.path("type").asText());
        assertEquals("agent-a", first.path("by").asText());
        assertEquals("eval", first.path("verb").asText());
        assertEquals("fix the form", first.path("reason").asText());
        assertFalse(first.path("at").asText().isBlank(), first.toString());
        assertEquals("upload", new ObjectMapper().readTree(lines.get(1)).path("verb").asText());
    }

    @Test
    void aRefusedOverrideWritesNothing() throws Exception {
        HumanLock.lock(project, "r2", "agent-a");
        String refused = HumanLock.check(project, "r2", "eval", new LinkedHashMap<>(Map.of("human-override", "x")), "agent-b");
        assertNotNull(refused);
        assertTrue(refused.contains("only for the session owner"), refused);
        assertFalse(Files.exists(HumanLock.eventLog(project, "r2")));
    }
}
