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

/** P4 session-mode warning and the wait-any usage gate. */
class ConsumerFixesP4CliTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void sessionModeWithoutARunIdWarnsAndKeepsExitZero() throws Exception {
        Capture capture = run("session-mode", "--human=on");
        assertEquals(0, capture.code, capture.err + capture.out);
        assertTrue(capture.err.contains("warning: the human lock applies to the default run id"), capture.err);
        assertTrue(capture.err.contains("fix: --run-id=<helper>"), capture.err);
        assertFalse(capture.err.contains("error:"), capture.err);
        JsonNode json = JSON.readTree(capture.out);
        assertEquals("on", json.path("human").asText());
        assertEquals("control", json.path("tier").asText());
        assertEquals("session", json.path("caller").asText());
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/human-lock/session.json")));
    }

    @Test
    void sessionModeWithARunIdDoesNotWarn() throws Exception {
        Capture capture = run("session-mode", "--human=on", "--run-id=helper");
        assertEquals(0, capture.code, capture.err + capture.out);
        assertFalse(capture.err.contains("warning:"), capture.err);
        JsonNode json = JSON.readTree(capture.out);
        assertEquals("on", json.path("human").asText());
        assertEquals("helper", json.path("caller").asText());
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/human-lock/helper.json")));
    }

    @Test
    void sessionModeOffWithoutARunIdDoesNotWarn() throws Exception {
        Capture capture = run("session-mode", "--human=off");
        assertEquals(0, capture.code, capture.err + capture.out);
        assertFalse(capture.err.contains("warning:"), capture.err);
        JsonNode json = JSON.readTree(capture.out);
        assertEquals("off", json.path("human").asText());
        assertEquals("session", json.path("caller").asText());
    }

    @Test
    void covers_command_wait_any() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"wait-any", temp.toString()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(2, code, stderr.toString(StandardCharsets.UTF_8) + out);
        assertTrue(out.contains("wait-any needs --answer, --chat, or --watch"), out);
    }

    private Capture run(String... args) {
        String[] argv = new String[args.length + 1];
        argv[0] = args[0];
        argv[1] = temp.toString();
        if (args.length > 1) System.arraycopy(args, 1, argv, 2, args.length - 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                argv,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
