package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact {@code covers_command_*} names the Workbench CLI coverage gate requires
 * for the R5 agent commands that share the spine session.
 */
class SpineCommandCoverageTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void covers_command_spine() throws Exception {
        JsonNode created = run("spine", "session", "new");
        assertFalse(created.path("state").path("session").asText().isBlank(), created.toString());
        assertFalse(WorkbenchAgentCommands.MAJOR_FLAGS.isEmpty());
    }

    @Test
    void covers_command_chat() throws Exception {
        String id = run("spine", "session", "new").path("state").path("session").asText();
        JsonNode sent = run("chat", "send", "--text=hello", "--session=" + id);
        assertEquals(id, sent.path("channel").asText(), sent.toString());
        assertFalse(WorkbenchAgentCommands.MAJOR_FLAGS.isEmpty());
    }

    @Test
    void covers_command_inject() throws Exception {
        String id = run("spine", "session", "new").path("state").path("session").asText();
        JsonNode on = run("inject", "on", "--session=" + id);
        assertTrue(on.path("inject").asBoolean(false), on.toString());
        assertFalse(WorkbenchAgentCommands.MAJOR_FLAGS.isEmpty());
    }

    @Test
    void covers_command_lease() throws Exception {
        String id = run("spine", "session", "new").path("state").path("session").asText();
        JsonNode taken = run("lease", "take", "--target=ui", "--session=" + id);
        assertTrue(taken.path("ok").asBoolean(false), taken.toString());
        Path lease = project.resolve(".pickleball/workbench-ui/leases").resolve(id).resolve("ui.json");
        assertTrue(Files.isRegularFile(lease), lease.toString());
        assertFalse(WorkbenchAgentCommands.MAJOR_FLAGS.isEmpty());
    }

    @Test
    void covers_command_point() throws Exception {
        String id = run("spine", "session", "new").path("state").path("session").asText();
        JsonNode pointed = run("point", "--session=" + id, "--handle=h9");
        assertEquals("h9", pointed.path("handle").asText(), pointed.toString());
        assertFalse(WorkbenchAgentCommands.MAJOR_FLAGS.isEmpty());
    }

    @Test
    void covers_command_progress() throws Exception {
        String id = run("spine", "session", "new").path("state").path("session").asText();
        JsonNode card = run("progress", "--pct=40", "--session=" + id);
        assertEquals(40, card.path("pct").asInt(-1), card.toString());
        assertFalse(WorkbenchAgentCommands.MAJOR_FLAGS.isEmpty());
    }

    private JsonNode run(String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] argv = new String[args.length + 1];
        System.arraycopy(args, 0, argv, 0, args.length);
        argv[args.length] = project.toString();
        int code = WorkbenchAgentCommands.run(
                argv,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8).trim();
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8) + text);
        return JSON.readTree(text);
    }
}
