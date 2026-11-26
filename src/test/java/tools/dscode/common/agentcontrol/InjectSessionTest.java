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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A new spine session is clean. inject on records a fixture attach. inject off clears it. */
class InjectSessionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void defaultSessionIsCleanAndTheSecondAttachStaysInjectedUntilOff() throws Exception {
        JsonNode created = run("spine", "session", "new");
        String id = created.path("state").path("session").asText();
        assertFalse(id.isBlank(), created.toString());
        assertFalse(created.path("inject").asBoolean(true), created.toString());
        assertFalse(SpineSession.injectEnabled(project, id));

        JsonNode on = run("inject", "on", "--session=" + id);
        assertTrue(on.path("ok").asBoolean(), on.toString());
        assertTrue(on.path("inject").asBoolean(false), on.toString());
        assertTrue(SpineSession.injectEnabled(project, id));

        String target = "cdp://fixture-" + id;
        SpineSession.attach(project, id, target);
        SpineSession.attach(project, id, target);
        assertTrue(SpineSession.injected(project, id, target));

        JsonNode off = run("inject", "off", "--session=" + id);
        assertTrue(off.path("ok").asBoolean(), off.toString());
        assertFalse(off.path("inject").asBoolean(true), off.toString());
        assertFalse(SpineSession.injectEnabled(project, id));
        assertFalse(SpineSession.injected(project, id, target));
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
