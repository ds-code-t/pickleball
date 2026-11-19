package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentfast.FallbackLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Phase 4: desktop uses the same act verb and does not open a live desktop bus. */
class DesktopSameVerbTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void covers_command_act() throws Exception {
        FallbackLog.resetForTests();
        JsonNode pid = agent("act", "--target=desktop:4242/window:main", "--run-id=desk-pid");
        JsonNode app = agent("act", "--target=desktop:app~Notes/window:mail", "--run-id=desk-app");
        assertEquals("act", pid.path("op").asText());
        assertEquals("act", app.path("op").asText());
        assertEquals("desktop", pid.path("used").asText());
        assertEquals("desktop", app.path("used").asText());
        assertEquals("4242", pid.path("pid").asText());
        assertEquals("main", pid.path("window").asText());
        assertEquals("Notes", app.path("app").asText());
        assertEquals("mail", app.path("window").asText());
        assertEquals("act", pid.path("chain").asText());
        assertFalse(pid.path("applied").asBoolean());
        assertFalse(app.path("applied").asBoolean());
        assertFalse(pid.toString().toLowerCase().contains("atspi"), pid.toString());
    }

    private JsonNode agent(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, out + stderr.toString(StandardCharsets.UTF_8));
        return JSON.readTree(out);
    }
}
