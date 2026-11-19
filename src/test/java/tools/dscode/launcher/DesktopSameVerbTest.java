package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.desktop.DesktopTree;
import org.junit.jupiter.api.AfterEach;
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

/** Phase 4: find and act use the desktop backend with the same verb strings. */
class DesktopSameVerbTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void resetDesktop() {
        DesktopTree.resetForTests();
    }

    @Test
    void covers_command_act() throws Exception {
        Capture unknown = agent("find", "--target=desktop:999999", "--query=Save", "--run-id=desk-missing");
        assertEquals(1, unknown.code, unknown.out + unknown.err);
        assertTrue(unknown.out.contains("unknown target"), unknown.out);
        assertFalse(unknown.out.contains("\"ok\":true"), unknown.out);

        Path fixture = temp.resolve("notes.json");
        Files.writeString(fixture, """
                {"role":"window","name":"Notes","x":0,"y":0,"width":200,"height":100,"children":[
                  {"role":"button","name":"Save","x":10,"y":10,"width":40,"height":20}
                ]}
                """);
        Capture found = agent(
                "find", "--target=desktop:app~Notes", "--fixture=" + fixture, "--query=Save", "--run-id=desk");
        assertEquals(0, found.code, found.err + found.out);
        JsonNode hit = JSON.readTree(found.out);
        assertTrue(hit.path("applied").asBoolean(), found.out);
        assertEquals("desktop", hit.path("used").asText(), found.out);
        assertEquals("Save", hit.path("hits").get(0).path("name").asText(), found.out);
        String ref = hit.path("ref").asText();
        assertTrue(ref.startsWith("ref:desktop:0:"), ref);

        Capture acted = agent("act", "--target=" + ref, "--kind=click", "--run-id=desk");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode act = JSON.readTree(acted.out);
        assertTrue(act.path("applied").asBoolean(), acted.out);
        assertEquals("desktop:1", act.path("docVersion").asText(), acted.out);

        Capture stale = agent("act", "--target=" + ref, "--kind=click", "--run-id=desk");
        assertEquals(1, stale.code, stale.err + stale.out);
        assertTrue(stale.out.contains("stale_ref"), stale.out);
        assertFalse(stale.out.contains("\"ok\":true"), stale.out);
    }

    private Capture agent(String... args) {
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
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
