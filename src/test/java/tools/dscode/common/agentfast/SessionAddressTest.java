package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.launcher.WorkbenchAgentCommands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Two agents on one spine session get two envelope agents and two layer ids. */
class SessionAddressTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void twoAgentsDoNotShareAnOverlayLayer() throws Exception {
        JsonNode created = run("spine", "session", "new");
        String id = created.path("state").path("session").asText();
        JsonNode alpha = run("spine", "session", "show", "--session=" + id, "--agent=alpha");
        JsonNode beta = run("spine", "session", "show", "--session=" + id, "--agent=beta");
        assertEquals("alpha", alpha.path("state").path("agent").asText(), alpha.toString());
        assertEquals("beta", beta.path("state").path("agent").asText(), beta.toString());
        assertEquals("alpha", alpha.path("state").path("layer").asText(), alpha.toString());
        assertEquals("beta", beta.path("state").path("layer").asText(), beta.toString());
        assertNotEquals(alpha.path("state").path("layer").asText(), beta.path("state").path("layer").asText());
    }

    private JsonNode run(String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        System.arraycopy(args, 1, command, 1, args.length - 1);
        command[args.length] = project.toString();
        int code = WorkbenchAgentCommands.run(
                command,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8).trim();
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8) + text);
        return JSON.readTree(text);
    }
}
