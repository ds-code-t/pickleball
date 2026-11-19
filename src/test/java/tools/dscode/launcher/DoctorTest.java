package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 3: doctor reports chrome and health without requiring a live session. */
class DoctorTest {
    @TempDir
    Path temp;

    @Test
    void covers_command_doctor() {
        Capture reported = run("doctor", temp.toString());
        assertEquals(0, reported.code, reported.err);
        assertTrue(reported.out.contains("chrome="), reported.out);
        assertTrue(reported.out.contains("chrome=present") || reported.out.contains("chrome=absent"), reported.out);
        assertTrue(reported.out.contains("health=absent"), reported.out);
        assertTrue(reported.out.contains("session=absent"), reported.out);
        assertTrue(reported.out.contains("bridge=absent"), reported.out);
        assertTrue(WorkbenchAgentCommands.class.getName().contains("WorkbenchAgentCommands"));
    }

    private Capture run(String... args) {
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
