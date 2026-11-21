package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.coordination.AgentCoordination;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** gc-runs --dry-run lists candidates. An unknown flag deletes nothing. */
class GcRunsDryRunTest {
    @TempDir
    Path temp;

    @Test
    void dryRunListsAndUnknownFlagDeletesNothing() throws Exception {
        AgentCoordination.Run run = AgentCoordination.begin(
                temp, AgentCoordination.Request.of("run-dry", "agent-dry", null, null, null, null, "test"));
        Path cookies = run.browserProfileDirectory().resolve("worker").resolve("Cookies");
        Files.createDirectories(cookies.getParent());
        Files.writeString(cookies, "stay");
        AgentCoordination.finish(temp, run.runId(), null, "STOPPED", Instant.now().minus(Duration.ofHours(2)), null);
        assertTrue(Files.isRegularFile(cookies));

        Capture dry = run("gc-runs", "--dry-run");
        assertEquals(0, dry.code, dry.err + dry.out);
        assertTrue(dry.out.contains("would-remove"), dry.out);
        assertTrue(dry.out.contains("Cookies"), dry.out);
        assertTrue(dry.out.contains("deleted=false"), dry.out);
        assertTrue(dry.out.contains("profiles-removed=1"), dry.out);
        assertTrue(Files.isRegularFile(cookies), "dry-run deleted " + cookies);

        Capture unknown = run("gc-runs", "--sweep");
        assertEquals(2, unknown.code, unknown.out);
        assertTrue(unknown.err.contains("does not use --sweep"), unknown.err);
        assertTrue(Files.isRegularFile(cookies), "unknown flag deleted " + cookies);

        Capture real = run("gc-runs");
        assertEquals(0, real.code, real.err + real.out);
        assertFalse(real.out.contains("would-remove"), real.out);
        assertFalse(Files.exists(cookies));
    }

    private Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
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
