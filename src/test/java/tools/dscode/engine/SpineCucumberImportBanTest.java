package tools.dscode.engine;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The native engine class files, outside the Cucumber adapter, do not name Cucumber or Playwright.
 */
class SpineCucumberImportBanTest {
    @Test
    void nativeEngineClassFilesDoNotNameCucumberOrPlaywright() throws Exception {
        Path root = Path.of("build/classes/java/main/tools/dscode/engine");
        assertTrue(Files.isDirectory(root), root.toString());
        byte[] cucumber = "io/cucumber/".getBytes(StandardCharsets.UTF_8);
        byte[] playwright = "com/microsoft/playwright/".getBytes(StandardCharsets.UTF_8);
        List<String> hits = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(path -> path.getFileName().toString().endsWith(".class")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith("adapter/cucumber/")) continue;
                scanned++;
                byte[] bytes = Files.readAllBytes(file);
                if (indexOf(bytes, cucumber) >= 0 || indexOf(bytes, playwright) >= 0) hits.add(relative);
            }
        }
        assertTrue(scanned > 0, "no native engine class files");
        assertTrue(hits.isEmpty(), String.join("\n", hits));
    }

    private static int indexOf(byte[] bytes, byte[] needle) {
        if (needle.length == 0 || bytes.length < needle.length) return -1;
        for (int index = 0; index <= bytes.length - needle.length; index++) {
            boolean same = true;
            for (int offset = 0; offset < needle.length; offset++) {
                if (bytes[index + offset] != needle[offset]) {
                    same = false;
                    break;
                }
            }
            if (same) return index;
        }
        return -1;
    }
}
