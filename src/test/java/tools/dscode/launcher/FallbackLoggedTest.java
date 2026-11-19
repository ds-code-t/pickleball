package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentfast.FallbackLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 4: fallback chains log once per run and reason, with a date and a toggle. */
class FallbackLoggedTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void clearToggle() {
        System.clearProperty("pkb_fallback_log");
        System.clearProperty("pkb_composite_fallback_log");
        FallbackLog.resetForTests();
    }

    @Test
    void covers_command_wait() throws Exception {
        FallbackLog.resetForTests();
        System.setProperty("pkb_composite_fallback_log", "off");
        JsonNode first = run("wait", "--condition=mutation", "--target=browser:auto", "--run-id=wait-1");
        run("wait", "--condition=mutation", "--target=browser:auto", "--run-id=wait-1");
        assertEquals("event", first.path("mode").asText());
        assertEquals("wait", first.path("chain").asText());
        assertFalse(first.path("applied").asBoolean());
        Path file = temp.resolve(".pickleball/logs/fallback-chain.jsonl");
        List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("\"chain\":\"wait\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"at\":"), lines.get(0));
        JsonNode poll = run("wait", "--condition=visible", "--target=browser:auto", "--run-id=wait-2");
        assertEquals("poll", poll.path("mode").asText());
        assertEquals(2, Files.readAllLines(file).size());
    }

    @Test
    void covers_command_watch() throws Exception {
        FallbackLog.resetForTests();
        System.setProperty("pkb_fallback_log", "off");
        JsonNode node = run("watch", "--condition=load", "--target=browser:auto", "--run-id=watch-off");
        assertEquals("event", node.path("mode").asText());
        assertEquals("watch", node.path("op").asText());
        assertFalse(Files.exists(temp.resolve(".pickleball/logs/fallback-chain.jsonl")));
    }

    private JsonNode run(String... args) throws Exception {
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
