package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentFastCliTest {
    @TempDir
    Path temp;

    @Test
    void covers_command_screenshot() {
        Capture missing = agent("screenshot", temp.toString());
        assertEquals(2, missing.code, missing.out + missing.err);
        assertTrue((missing.out + missing.err).contains("screenshot requires --out"), missing.out + missing.err);
        Capture none = agent("screenshot", temp.toString(), "--out=shot.png");
        assertEquals(1, none.code, none.out + none.err);
        assertTrue((none.out + none.err).contains("no live browser"), none.out + none.err);
        assertFalse(none.out.contains("base64"), none.out);
    }

    @Test
    void covers_command_selftest() {
        Capture missing = agent("selftest", temp.toString());
        assertEquals(2, missing.code, missing.out + missing.err);
        assertTrue((missing.out + missing.err).contains("selftest ui --cpu"), missing.out + missing.err);
    }

    private Capture agent(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
