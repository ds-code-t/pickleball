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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Typed ask, progress bounds, point rows, and overlay read receipts stay headless. */
class AskProgressPointTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void choiceRejectsAnUnknownOptionAndReceiptsStayHeadless() throws Exception {
        JsonNode created = run("spine", "session", "new");
        String session = created.path("state").path("session").asText();
        assertFalse(session.isBlank(), created.toString());

        JsonNode asked = run("ask", "--type=choice", "--prompt=Color", "--options=red,blue", "--session=" + session);
        String card = asked.path("id").asText();
        assertEquals("choice", asked.path("type").asText(), asked.toString());
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> AskCards.answer(project, session, card, "nope"));
        assertTrue(rejected.getMessage().contains("not listed"), rejected.getMessage());
        AskCards.answer(project, session, card, "red");
        JsonNode poll = run("ui-poll", "--session=" + session);
        assertEquals("choice", poll.path("type").asText(), poll.toString());
        assertEquals("red", poll.path("value").asText(), poll.toString());

        ByteArrayOutputStream progressOut = new ByteArrayOutputStream();
        ByteArrayOutputStream progressErr = new ByteArrayOutputStream();
        int progress = WorkbenchAgentCommands.run(
                new String[]{"progress", "--pct=140", "--session=" + session, project.toString()},
                new PrintStream(progressOut, true, StandardCharsets.UTF_8),
                new PrintStream(progressErr, true, StandardCharsets.UTF_8));
        assertEquals(2, progress, progressOut.toString(StandardCharsets.UTF_8));
        assertTrue(progressErr.toString(StandardCharsets.UTF_8).contains("0 through 100"), progressErr.toString(StandardCharsets.UTF_8));

        JsonNode pointed = run("point", "--session=" + session, "--handle=h9");
        assertEquals("h9", pointed.path("handle").asText(), pointed.toString());

        OverlayReceipts.record(project, session, "ov1");
        JsonNode before = item(run("overlay-poll", "--session=" + session), "ov1");
        assertTrue(before.path("read_at").isNull() || before.path("read_at").asText("").isBlank(), before.toString());
        OverlayReceipts.ack(project, session, "ov1");
        JsonNode after = item(run("overlay-poll", "--session=" + session), "ov1");
        assertFalse(after.path("read_at").asText("").isBlank(), after.toString());
    }

    private JsonNode item(JsonNode envelope, String id) {
        for (JsonNode row : envelope.path("items")) {
            if (id.equals(row.path("id").asText())) return row;
        }
        throw new AssertionError("missing " + id + " in " + envelope);
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
