package tools.dscode.launcher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every documented Workbench command in the first-read guide, the detail guide,
 * the 2.1.18 release notes, and the packaged copies of those files has a
 * help page. The pages are compared to an ASCII golden.
 */
class GuidanceCommandGoldenTest {
    private static final Path GOLDEN = Path.of("src/test/resources/guidance-golden/documented-help.txt");
    private static final Pattern EXEC_ARGS = Pattern.compile("-Dexec\\.args=([a-z][a-z0-9-]*)");
    private static final Pattern LEADING_VERB = Pattern.compile("(?m)^\\s*`?([a-z][a-z0-9-]*)(?:\\s|`|$)");
    private static final Pattern BACKTICK_VERB = Pattern.compile("`([a-z][a-z0-9-]*)");
    private static final List<Path> SOURCES = List.of(
            Path.of("docs/consumer-agent-guide.md"),
            Path.of("docs/workbench-agent-detail.md"),
            Path.of("docs/release-notes.md"),
            Path.of("src/main/resources/META-INF/pickleball/guidance/AGENT-GUIDE.md"),
            Path.of("src/main/resources/META-INF/pickleball/guidance/docs/workbench-agent-detail.md"),
            Path.of("src/main/resources/META-INF/pickleball/guidance/docs/release-notes.md"),
            Path.of("src/main/resources/META-INF/pickleball/guidance/docs/consumer-agent-guide.md")
    );

    @Test
    void documentedCommandsMatchTheHelpGolden() throws Exception {
        TreeSet<String> verbs = documentedVerbs();
        assertTrue(verbs.contains("discover"), verbs.toString());
        assertTrue(verbs.contains("isolate"), verbs.toString());
        assertTrue(verbs.contains("execute-step"), verbs.toString());
        assertTrue(verbs.contains("confirm"), verbs.toString());
        assertTrue(verbs.contains("workspace-show"), verbs.toString());
        assertTrue(verbs.contains("query"), verbs.toString());
        StringBuilder actual = new StringBuilder();
        actual.append("# help\n");
        Capture catalog = run("help");
        assertEquals(0, catalog.code, catalog.err);
        actual.append(ascii(catalog.out));
        for (String verb : verbs) {
            assertTrue(WorkbenchCommandLine.isKnownCommand(verb), "documented command is not a launcher verb: " + verb);
            Capture page = run("help", verb);
            assertEquals(0, page.code, verb + " " + page.err);
            assertFalse(page.out.isBlank(), verb);
            actual.append("\n# help ").append(verb).append('\n');
            actual.append(ascii(page.out));
        }
        String text = actual.toString().replace("\r\n", "\n");
        if (!Files.isRegularFile(GOLDEN) || Boolean.getBoolean("pkb.update.golden")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, text, StandardCharsets.US_ASCII);
        }
        String expected = Files.readString(GOLDEN, StandardCharsets.US_ASCII).replace("\r\n", "\n");
        assertEquals(expected, text);
    }

    private static TreeSet<String> documentedVerbs() throws Exception {
        TreeSet<String> verbs = new TreeSet<>();
        for (Path source : SOURCES) {
            String body = Files.readString(source, StandardCharsets.UTF_8);
            if (source.getFileName().toString().equals("release-notes.md")) {
                int start = body.indexOf("## 2.1.18");
                int end = body.indexOf("## 2.1.17");
                if (start >= 0 && end > start) body = body.substring(start, end);
            }
            Matcher exec = EXEC_ARGS.matcher(body);
            while (exec.find()) accept(verbs, exec.group(1));
            Matcher leading = LEADING_VERB.matcher(body);
            while (leading.find()) accept(verbs, leading.group(1));
            Matcher ticks = BACKTICK_VERB.matcher(body);
            while (ticks.find()) accept(verbs, ticks.group(1));
        }
        return verbs;
    }

    private static void accept(TreeSet<String> verbs, String token) {
        if (token == null || token.isBlank()) return;
        if (WorkbenchCommandLine.isKnownCommand(token)) verbs.add(token);
    }

    private static Capture run(String... args) {
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

    /** Golden files stay ASCII. U+2026 is the Windows console diamond. */
    private static String ascii(String text) {
        return text.replace("\u2026", "...").replace("\r\n", "\n");
    }

    private record Capture(int code, String out, String err) {
    }
}
