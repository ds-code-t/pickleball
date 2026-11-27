package tools.dscode.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.VerbArgs;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code lease take} goes through {@link VerbDispatch}. {@code both} conflicts
 * with {@code ui}. The holder gates {@code ui page}.
 */
class LeaseGateTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void bothConflictsWithUiAndTheHolderGatesThePage() throws Exception {
        Result created = dispatch("spine", "session", "new");
        assertEquals(0, created.code, created.err);
        String id = JSON.readTree(created.out).path("state").path("session").asText();
        assertFalse(id.isBlank(), created.out);

        Result ui = dispatch("lease", "take", "--target=ui", "--session=" + id, "--agent=agent-1");
        assertEquals(0, ui.code, ui.err);

        Result both = dispatch("lease", "take", "--target=both", "--session=" + id, "--agent=agent-2");
        assertTrue(both.code != 0, both.out + both.err);
        assertTrue(both.err.contains("error:"), both.err);
        assertTrue(both.err.contains("fix:"), both.err);
        assertFalse(Files.exists(lease(id, "both")));

        Result browser = dispatch("lease", "take", "--target=browser", "--session=" + id, "--agent=agent-2");
        assertEquals(0, browser.code, browser.err);
        assertTrue(Files.isRegularFile(lease(id, "browser")));
        assertTrue(Files.isRegularFile(project.resolve(".pickleball").resolve("workbench").resolve("pause-hold")));

        Path html = project.resolve("page.html");
        Files.writeString(html, "<script>fetch(\"/store\", {method:\"POST\"})</script>\n", StandardCharsets.UTF_8);
        Result other = dispatch(
                "ui", "page", "--session=" + id, "--name=other", "--html-file=" + html, "--agent=agent-2");
        assertTrue(other.code != 0, other.out + other.err);
        assertTrue(other.err.contains("error:"), other.err);
        assertTrue(other.err.contains("fix:"), other.err);
        assertFalse(Files.exists(page(id, "other")));

        Result owner = dispatch(
                "ui", "page", "--session=" + id, "--name=owner", "--html-file=" + html, "--agent=agent-1");
        assertEquals(0, owner.code, owner.err);
        assertTrue(Files.isRegularFile(page(id, "owner")));
    }

    private Path lease(String id, String target) {
        return project.resolve(".pickleball").resolve("workbench-ui").resolve("leases").resolve(id).resolve(target + ".json");
    }

    private Path page(String id, String name) {
        return project.resolve(".pickleball").resolve("workbench-ui").resolve("generated").resolve(id).resolve(name + ".html");
    }

    private Result dispatch(String... tokens) {
        String[] argv = new String[tokens.length + 1];
        System.arraycopy(tokens, 0, argv, 0, tokens.length);
        argv[tokens.length] = project.toAbsolutePath().toString();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = VerbDispatch.run(
                tokens[0],
                VerbArgs.capture(argv),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String out, String err) {
    }
}
