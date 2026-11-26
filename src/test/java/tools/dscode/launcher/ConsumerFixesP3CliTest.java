package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.LiveHostFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P3 command-line replies: unknown positionals, isolate-worker hint, and doctor labels. */
class ConsumerFixesP3CliTest {
    @TempDir
    Path temp;

    @Test
    void unknownPositionalIsAnErrorAndDoesNotBecomeAProject() {
        String token = "Case\\ menu";
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> WorkbenchCommandLine.parse(new String[]{"chat-send", token, "--text=hello"}));
        assertTrue(failure.getMessage().contains("unknown positional"), failure.getMessage());
        assertTrue(failure.getMessage().contains(token), failure.getMessage());
        assertTrue(failure.getMessage().contains("error:"), failure.getMessage());
        assertFalse(Files.exists(Path.of(token)));
    }

    @Test
    void recordStillAcceptsAScratchDirectoryThatDoesNotExistYet() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(new String[]{
                "record", "build/p3-scratch-missing"
        });
        String project = parsed.project().toString().replace('\\', '/');
        assertTrue(project.endsWith("build/p3-scratch-missing"), project);
    }

    @Test
    void positionalVerbsKeepTheirWords() {
        WorkbenchCommandLine.Parsed pw = WorkbenchCommandLine.parse(new String[]{"pw", "page", "goto"});
        assertEquals("pw", pw.command());
        WorkbenchCommandLine.Parsed query = WorkbenchCommandLine.parse(new String[]{"query", "runs"});
        assertEquals("query", query.command());
    }

    @Test
    void startHostWithoutAWorkerNamesTheInspectCommand() throws Exception {
        LiveHostFile.write(temp, new LiveHostFile.Host(
                "http://127.0.0.1/", "token", 1, ProcessHandle.current().pid(), 0,
                "p3", false, "chrome", false, "", false, 0, 0));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"inspect-page", temp.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = err.toString(StandardCharsets.UTF_8);
        assertEquals(2, code, text);
        assertTrue(text.contains("inspect-page needs an isolate worker"), text);
        assertTrue(text.contains("isolate, then retry inspect-page"), text);
        assertFalse(text.contains("start a session first"), text);
    }

    @Test
    void emptySessionStillSaysStartASessionFirst() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"find-element", temp.toString(), "--category=Button", "--text=Save"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = err.toString(StandardCharsets.UTF_8);
        assertEquals(2, code, text);
        assertTrue(text.contains("start a session first"), text);
    }

    @Test
    void unknownFieldFixIsOneLine() throws Exception {
        Path fixture = notes();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"find", temp.toString(), "--target=desktop:app~Notes", "--fixture=" + fixture,
                        "--query=Save", "--fields=zzz", "--run-id=p3-fields"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = err.toString(StandardCharsets.UTF_8);
        assertTrue(code != 0, text);
        assertTrue(text.contains("unknown field \"zzz\""), text);
        assertTrue(text.contains("Pass one of the known names."), text);
        assertFalse(text.contains("Usage:"), text);
    }

    @Test
    void timeoutFixIsOneLine() throws Exception {
        Path fixture = notes();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"wait", temp.toString(), "--target=desktop:app~Notes", "--fixture=" + fixture,
                        "--condition=text=missing-token", "--timeout=1", "--run-id=p3-timeout"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = err.toString(StandardCharsets.UTF_8);
        assertTrue(code != 0, text);
        assertTrue(text.contains("pass --timeout=<ms>"), text);
        assertFalse(text.contains("Usage:"), text);
    }

    @Test
    void doctorLabelsTheStartProbe() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WorkbenchAgentCommands.run(
                new String[]{"doctor", temp.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("protocols="), text);
        assertTrue(text.contains("protocols.start="), text);
    }

    private Path notes() throws Exception {
        Path fixture = temp.resolve("notes.json");
        Files.writeString(fixture, """
                {"role":"window","name":"Notes","x":0,"y":0,"width":200,"height":100,"children":[
                  {"role":"button","name":"Save","x":10,"y":10,"width":40,"height":20}
                ]}
                """);
        return fixture;
    }
}
