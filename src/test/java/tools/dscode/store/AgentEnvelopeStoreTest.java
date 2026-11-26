package tools.dscode.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code store status --json} is an agent envelope. The old status object is {@code items}. */
class AgentEnvelopeStoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void statusJsonKeepsTheOldObjectUnderItems() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = StoreCli.execute(
                new String[] {"store", "status", "--json", "--project=" + project.toAbsolutePath()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8) + text);
        JsonNode envelope = JSON.readTree(text);
        assertEquals(1, envelope.path("v").asInt(), text);
        assertEquals("store status", envelope.path("op").asText(), text);
        assertTrue(envelope.path("ok").asBoolean(), text);
        assertFalse(envelope.has("command"), text);
        assertFalse(envelope.has("count"), text);
        assertFalse(envelope.has("total"), text);
        assertFalse(envelope.has("rows"), text);
        JsonNode status = envelope.path("items").path(0);
        assertTrue(status.has("database"), text);
        assertTrue(status.has("path"), text);
        assertTrue(status.has("enabled"), text);
        assertEquals(status, StoreJson.read(text));
    }
}
