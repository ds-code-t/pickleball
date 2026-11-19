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

/** Phase 4: one selector for browser, cdp, auto, and ref, through the CLI. */
class TargetAgnosticApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void covers_command_find() throws Exception {
        FallbackLog.resetForTests();
        JsonNode node = agent("find", "--target=browser:sess/tab:t1/frame:f2", "--run-id=find-1");
        assertEquals("find", node.path("op").asText());
        assertEquals("browser", node.path("kind").asText());
        assertEquals("sess", node.path("session").asText());
        assertEquals("t1", node.path("tab").asText());
        assertEquals("f2", node.path("frame").asText());
        assertFalse(node.path("applied").asBoolean());
        assertEquals("none", node.path("used").asText());
        assertEquals("locate", node.path("chain").asText());
    }

    @Test
    void covers_command_snap() throws Exception {
        FallbackLog.resetForTests();
        JsonNode node = agent("snap", "--target=browser:auto", "--run-id=snap-1");
        assertEquals("snap", node.path("op").asText());
        assertEquals("browser", node.path("kind").asText());
        assertEquals(true, node.path("auto").asBoolean());
        assertEquals("snapshot", node.path("chain").asText());
        assertEquals("none", node.path("used").asText());
        assertFalse(node.path("applied").asBoolean());
    }

    @Test
    void covers_command_explain() throws Exception {
        FallbackLog.resetForTests();
        JsonNode node = agent("explain", "--target=browser:cdp=ws://127.0.0.1:9/devtools/page/ABC/tab:t1/frame:sub", "--run-id=explain-1");
        assertEquals("explain", node.path("op").asText());
        assertEquals("ws://127.0.0.1:9/devtools/page/ABC", node.path("cdp").asText());
        assertEquals("t1", node.path("tab").asText());
        assertEquals("sub", node.path("frame").asText());
        assertEquals("locate", node.path("chain").asText());
        assertFalse(node.path("applied").asBoolean());
    }

    @Test
    void covers_command_capture() throws Exception {
        FallbackLog.resetForTests();
        JsonNode node = agent("capture", "--target=ref:12:e:9", "--run-id=capture-1");
        assertEquals("capture", node.path("op").asText());
        assertEquals("ref", node.path("kind").asText());
        assertEquals("12:e", node.path("docVersion").asText());
        assertEquals("9", node.path("handle").asText());
        assertEquals("ref", node.path("used").asText());
        assertEquals("snapshot", node.path("chain").asText());
        assertFalse(node.path("applied").asBoolean());
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
