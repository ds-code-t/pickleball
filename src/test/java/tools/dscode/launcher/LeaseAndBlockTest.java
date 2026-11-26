package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two lease takes on one target fail the second. A clean session cannot block input. */
class LeaseAndBlockTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void secondTakeFailsAndACleanSessionCannotBlockInput() throws Exception {
        Run created = run("spine", "session", "new");
        assertEquals(0, created.code, created.err);
        String id = JSON.readTree(created.out).path("state").path("session").asText();
        assertFalse(id.isBlank(), created.out);

        Run first = run("lease", "take", "--target=ui", "--session=" + id);
        assertEquals(0, first.code, first.err);
        assertTrue(JSON.readTree(first.out).path("ok").asBoolean(), first.out);
        Path lease = project.resolve(".pickleball").resolve("workbench-ui").resolve("leases").resolve(id).resolve("ui.json");
        assertTrue(Files.isRegularFile(lease), lease.toString());

        Run second = run("lease", "take", "--target=ui", "--session=" + id);
        assertTrue(second.code != 0, second.out);
        assertTrue(second.err.contains("error:"), second.err);
        assertTrue(second.err.contains("fix:"), second.err);
        assertTrue(Files.isRegularFile(lease));

        Run blockedClean = run("input", "block", "--on", "--session=" + id);
        assertTrue(blockedClean.code != 0, blockedClean.out);
        assertTrue(blockedClean.err.contains("error:"), blockedClean.err);
        assertTrue(blockedClean.err.contains("fix: inject on"), blockedClean.err);
        assertFalse(SpineSession.inputBlocked(project, id));

        Run injected = run("inject", "on", "--session=" + id);
        assertEquals(0, injected.code, injected.err);

        Run blocked = run("input", "block", "--on", "--session=" + id);
        assertEquals(0, blocked.code, blocked.err);
        assertTrue(JSON.readTree(blocked.out).path("inputBlocked").asBoolean(), blocked.out);
        assertTrue(SpineSession.inputBlocked(project, id));

        Run clear = run("input", "block", "--off", "--session=" + id);
        assertEquals(0, clear.code, clear.err);
        assertFalse(JSON.readTree(clear.out).path("inputBlocked").asBoolean(true), clear.out);
        assertFalse(SpineSession.inputBlocked(project, id));

        Path good = project.resolve("good.html");
        String page = "<script>fetch(\"/store\", {method:\"POST\"})</script>\n";
        Files.writeString(good, page, StandardCharsets.UTF_8);
        Run written = run("ui", "page", "--session=" + id, "--name=hello", "--html-file=" + good);
        assertEquals(0, written.code, written.err);
        Path generated = project.resolve(".pickleball").resolve("workbench-ui").resolve("generated").resolve(id).resolve("hello.html");
        assertEquals(page, Files.readString(generated, StandardCharsets.UTF_8));

        Path bad = project.resolve("bad.html");
        Files.writeString(bad, "<script>fetch(\"/not-a-verb\")</script>\n", StandardCharsets.UTF_8);
        Run refused = run("ui", "page", "--session=" + id, "--name=nope", "--html-file=" + bad);
        assertTrue(refused.code != 0, refused.out);
        assertTrue(refused.err.contains("error:"), refused.err);
        assertTrue(refused.err.contains("fix:"), refused.err);
        assertFalse(Files.exists(project.resolve(".pickleball").resolve("workbench-ui").resolve("generated").resolve(id).resolve("nope.html")));

        Run ended = run("spine", "session", "end", "--session=" + id);
        assertEquals(0, ended.code, ended.err);
        assertFalse(Files.exists(generated));
        assertFalse(Files.exists(lease));
    }

    private Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] command = new String[args.length + 1];
        System.arraycopy(args, 0, command, 0, args.length);
        command[args.length] = project.toString();
        int code = WorkbenchAgentCommands.run(
                command,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8).trim(), err.toString(StandardCharsets.UTF_8));
    }

    private record Run(int code, String out, String err) {
    }
}
