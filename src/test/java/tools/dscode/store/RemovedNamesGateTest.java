package tools.dscode.store;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The removed store names are gone from the tree except the three planning
 * files. The pattern is built from pieces so this source does not contain it.
 */
class NoDo\u006ctLeftTest {
    @Test
    void removedNamesAreGone() throws Exception {
        String pattern = ("do" + "lt") + "|" + ("maria" + "db");
        Path root = repoRoot();
        String content = git(root, "grep", "-niE", pattern, "--", ".",
                ":(exclude)docs/release-notes.md",
                ":(exclude)docs/roadmaps/3.0.0-embedded-store-roadmap.md",
                ":(exclude)prompts/build-3.0.0-embedded-store.md");
        assertEquals("", content.trim(), content);
        Pattern names = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        StringBuilder hits = new StringBuilder();
        for (String line : git(root, "ls-files").split("\n", -1)) {
            if (line.isEmpty() || !names.matcher(line).find()) continue;
            if (hits.length() > 0) hits.append('\n');
            hits.append(line);
        }
        assertEquals("", hits.toString());
    }

    private static String git(Path root, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        assertTrue(code == 0 || code == 1, "git exited " + code + "\n" + text);
        return text;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) throw new IllegalStateException("settings.gradle not found");
        return dir;
    }
}
