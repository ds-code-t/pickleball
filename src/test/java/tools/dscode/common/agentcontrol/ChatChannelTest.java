package tools.dscode.common.agentcontrol;

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
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Default chat channel is the spine session. {@code pkb_chat_channel=run} shares one channel. */
class ChatChannelTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void defaultChannelIsTheSessionAndRunModeSharesOneChannel() throws Exception {
        String previous = System.getProperty("pkb_chat_channel");
        try {
            System.clearProperty("pkb_chat_channel");
            JsonNode created = run("spine", "session", "new");
            String id = created.path("state").path("session").asText();
            assertFalse(id.isBlank(), created.toString());

            JsonNode sent = run("chat", "send", "--text=hello", "--session=" + id);
            assertEquals(id, sent.path("channel").asText(), sent.toString());
            assertEquals(id, sent.path("message").path("channel").asText(), sent.toString());

            JsonNode linked = run("chat", "send", "--text=open it", "--ref=h1", "--action=open", "--session=" + id);
            assertEquals("h1", linked.path("ref").asText(), linked.toString());
            assertEquals("open", linked.path("action").asText(), linked.toString());
            assertEquals("h1", linked.path("message").path("ref").asText(), linked.toString());
            assertEquals("open", linked.path("message").path("action").asText(), linked.toString());

            System.setProperty("pkb_chat_channel", "run");
            JsonNode first = run("chat", "send", "--text=one", "--session=" + id);
            JsonNode second = run("chat", "send", "--text=two", "--session=" + id);
            assertEquals("run", first.path("channel").asText(), first.toString());
            assertEquals("run", second.path("channel").asText(), second.toString());
        } finally {
            if (previous == null) System.clearProperty("pkb_chat_channel");
            else System.setProperty("pkb_chat_channel", previous);
        }
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
