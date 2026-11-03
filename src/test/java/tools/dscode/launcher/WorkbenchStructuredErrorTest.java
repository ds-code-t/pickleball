package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.DiagnosticQuery;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every agent-facing failure is {@code error} plus {@code fix} from the one usage table. */
class WorkbenchStructuredErrorTest {
    @TempDir
    Path temp;

    @Test
    void unknownCommandFixIsTheNearestCommand() {
        String message = WorkbenchCliHelp.unknown("disover");
        assertTrue(message.contains("error: Unknown Workbench command: disover"), message);
        assertTrue(message.contains("fix: discover\n"), message);
        assertFalse(message.contains("fix: Usage:"), message);
        assertTrue(message.contains("Did you mean: discover"), message);
    }

    @Test
    void missingSessionIdFixComesFromTheUsageTable() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchCommandLine.parse(new String[]{"session", "info"})
        );
        String message = failure.getMessage();
        assertTrue(message.startsWith("error: missing sessionId\n"), message);
        assertTrue(message.contains("fix: " + WorkbenchCliHelp.usage("session-info").strip()), message);
        assertTrue(message.contains("Usage: session info <sessionId> [--json]"), message);
    }

    @Test
    void sessionRunIdIsTheSessionIdAndDeprecatedAliasesNotice() {
        WorkbenchCommandLine.Parsed session = WorkbenchCommandLine.parse(
                new String[]{"session", "info", "--run-id", "alias-1"}
        );
        assertEquals("session-info", session.command());
        assertEquals("alias-1", session.coordination().id());
        assertTrue(WorkbenchCommandLine.aliasNotices().contains("notice: session info --run-id is the session id"));

        WorkbenchCommandLine.parse(new String[]{"discover", "--runId=alias-1"});
        assertTrue(WorkbenchCommandLine.aliasNotices().contains("notice: --runId is --run-id"));
        assertFalse(WorkbenchCommandLine.aliasNotices().contains("notice: session info --run-id is the session id"));
    }

    @Test
    void noteAndQueryErrorsUseTheSameShape() {
        Capture note = run("note", temp.toString());
        assertEquals(2, note.code);
        assertTrue(note.err.startsWith("error: missing --text\n"), note.err);
        assertTrue(note.err.contains("fix: " + WorkbenchCliHelp.usage("note").strip()), note.err);

        Capture query = run("query", temp.toString());
        assertEquals(2, query.code);
        assertTrue(query.err.contains("error: missing op"), query.err);
        assertTrue(query.err.contains("fix: " + WorkbenchCliHelp.usage("query").strip()), query.err);
        assertTrue(query.out.contains("\"error\":\"missing op\""), query.out);
        assertTrue(query.out.contains("\"fix\":"), query.out);

        DiagnosticQuery.Outcome outcome = DiagnosticQuery.query(temp, Map.of("op", "search"));
        assertEquals("missing text", outcome.body().get("error"));
        assertTrue(String.valueOf(outcome.body().get("fix")).contains("Usage: query search"), outcome.json());
    }

    @Test
    void runVarFailureKeepsTheTableExample() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchCommandLine.parse(new String[]{"discover", "--runvar"})
        );
        String message = failure.getMessage();
        assertTrue(message.startsWith("error: --runvar requires key=value\n"), message);
        assertTrue(message.contains("fix: Usage: --runvar=key=value"), message);
        assertTrue(message.contains("pkb_browser=CHROME_HEADLESS"), message);
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
