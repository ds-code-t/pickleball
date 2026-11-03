package tools.dscode.common.composite;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct {@code findElement} / {@code Actions} calls may exist only in the
 * legacy branch. Every other source file must call {@code ElementOperations}.
 */
class PrimaryPathAuditTest {
    private static final Set<String> LEGACY = Set.of(
            "ElementOperations.java",
            "HumanInteractions.java",
            "KeyParser.java"
    );
    private static final Pattern CALL = Pattern.compile(
            "(?<!ElementOperations)\\.(findElement|findElements)\\(|new Actions\\("
    );

    @Test
    void primaryPathDoesNotCallWebDriverFindOrActions() throws IOException {
        Path root = Path.of("src/main");
        List<String> leaks = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".java") || name.endsWith(".aj");
            }).forEach(path -> scan(path, leaks));
        }
        assertTrue(leaks.isEmpty(), String.join("\n", leaks));
    }

    @Test
    void liveInspectRoutesThroughElementOperations() throws IOException {
        String source = Files.readString(Path.of(
                "pickleball-control-api/src/main/java/tools/dscode/control/api/ElementControl.java"));
        assertTrue(source.contains("ElementOperations.describe"), source);
        assertTrue(!source.contains("driver.findElement"), source);
        assertTrue(!source.contains("driver.findElements"), source);
        String pageQuery = Files.readString(Path.of(
                "pickleball-control-api/src/main/java/tools/dscode/control/api/PageElementQuery.java"));
        assertTrue(pageQuery.contains("ElementOperations.describe"), pageQuery);
        assertTrue(!pageQuery.contains("driver.findElement"), pageQuery);
        assertTrue(!pageQuery.contains("driver.findElements"), pageQuery);
    }

    private static void scan(Path path, List<String> leaks) {
        String file = path.getFileName().toString();
        if (LEGACY.contains(file)) return;
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException failure) {
            leaks.add(path + " unreadable");
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue;
            if (CALL.matcher(lines.get(i)).find()) {
                leaks.add(path + ":" + (i + 1) + " " + line);
            }
        }
    }
}
