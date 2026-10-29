package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.SessionDescriptor;
import tools.dscode.control.protocol.SessionDescriptorWriter;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchSessionQueryTest {
    @TempDir
    Path temp;

    @Test
    void emptyListPrintsNothingLive() {
        Capture text = run("session", "list", temp.toString());
        assertEquals(0, text.code);
        assertEquals("(no live sessions)\n", text.out);
        assertEquals("", text.err);

        Capture json = run("session", "list", "--json", temp.toString());
        assertEquals(0, json.code);
        assertEquals("[]\n", json.out);
    }

    @Test
    void singleSessionInfoMatchesTheDescriptorFile() throws Exception {
        Instant started = Instant.parse("2026-10-29T15:41:00Z");
        SessionDescriptor live = new SessionDescriptor(
                "session-1",
                "run-1",
                temp.resolve("workspace").toAbsolutePath().normalize().toString(),
                "chrome",
                "127.0.0.1:9222",
                "https://example.test/",
                List.of("window-a"),
                SessionDescriptor.State.RUNNING,
                started,
                started.plusSeconds(2)
        );
        SessionDescriptor stopped = new SessionDescriptor(
                "session-stopped",
                "run-stopped",
                temp.resolve("workspace").toAbsolutePath().normalize().toString(),
                "edge",
                null,
                null,
                List.of(),
                SessionDescriptor.State.STOPPED,
                started,
                started
        );
        SessionDescriptorWriter.publish(temp, live, true);
        SessionDescriptorWriter.publish(temp, stopped, true);
        Path file = SessionDescriptorWriter.file(temp, "run-1");
        String descriptor = Files.readString(file);

        Capture list = run("session", "list", temp.toString());
        assertEquals(0, list.code);
        assertEquals(
                "session-1\trun-1\trunning\tchrome\t127.0.0.1:9222\thttps://example.test/\n",
                list.out
        );

        Capture info = run("session", "info", "session-1", temp.toString());
        assertEquals(0, info.code);
        assertEquals(descriptor, info.out);

        Capture json = run("session", "info", "session-1", "--json", temp.toString());
        assertEquals(0, json.code);
        assertEquals(descriptor, json.out);

        Capture byRun = run("session", "info", "run-1", "--json", temp.toString());
        assertEquals(descriptor, byRun.out);

        Capture listed = run("session", "list", "--json", temp.toString());
        assertEquals(0, listed.code);
        assertTrue(listed.out.startsWith("[\n"));
        assertTrue(listed.out.endsWith("]\n"));
        String indented = "  " + descriptor.stripTrailing().replace("\n", "\n  ");
        assertTrue(listed.out.contains(indented));
        assertFalse(listed.out.contains("session-stopped"));

        Capture stoppedInfo = run("session", "info", "session-stopped", "--json", temp.toString());
        assertEquals(Files.readString(SessionDescriptorWriter.file(temp, "run-stopped")), stoppedInfo.out);
    }

    @Test
    void unknownIdIsAnErrorAndBareSessionStaysForwarded() {
        Capture unknown = run("session", "info", "missing-session", temp.toString());
        assertEquals(1, unknown.code);
        assertEquals("", unknown.out);
        assertTrue(unknown.err.contains("No session descriptor for missing-session."));

        Capture usage = run("session", "info");
        assertEquals(2, usage.code);
        assertTrue(usage.err.contains("Usage: session info <sessionId> [--json]"));

        WorkbenchCommandLine.Parsed session = WorkbenchCommandLine.parse(
                new String[]{"session", temp.toString()}
        );
        assertEquals("session", session.command());
        assertTrue(WorkbenchCommandLine.isForwardedCommand("session"));
        assertFalse(WorkbenchCommandLine.isAgentCoreCommand("session"));

        WorkbenchCommandLine.Parsed list = WorkbenchCommandLine.parse(
                new String[]{"session", "list", "--json", temp.toString()}
        );
        assertEquals("session-list", list.command());
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("session-list"));
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("session-info"));
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("session-hold"));
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("session-release"));
        assertEquals(temp.toAbsolutePath().normalize(), list.project());
    }

    @Test
    void holdAndReleaseMatchTheDescriptor() throws Exception {
        Instant started = Instant.parse("2026-10-29T16:10:00Z");
        SessionDescriptor running = new SessionDescriptor(
                "session-1",
                "run-1",
                temp.resolve("workspace").toAbsolutePath().normalize().toString(),
                "chrome",
                "127.0.0.1:9222",
                null,
                List.of(),
                SessionDescriptor.State.RUNNING,
                started,
                started
        );
        SessionDescriptorWriter.publish(temp, running, true);

        Capture held = run("session", "hold", "session-1", temp.toString());
        assertEquals(0, held.code);
        String file = Files.readString(SessionDescriptorWriter.file(temp, "run-1"));
        assertEquals(file, held.out);
        assertTrue(file.contains("\"state\": \"held\""));

        Capture released = run("session", "release", "run-1", temp.toString());
        assertEquals(0, released.code);
        String after = Files.readString(SessionDescriptorWriter.file(temp, "run-1"));
        assertEquals(after, released.out);
        assertTrue(after.contains("\"state\": \"running\""));

        Capture usage = run("session", "hold");
        assertEquals(2, usage.code);
        assertTrue(usage.err.contains("Usage: session hold <sessionId> [--json]"));

        Capture missing = run("session", "release", "missing", temp.toString());
        assertEquals(1, missing.code);
        assertTrue(missing.err.contains("No session descriptor for missing."));
    }

    private Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(
                code,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private record Capture(int code, String out, String err) {
    }
}
