package tools.dscode.engine;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The engine class files, including adapter, do not name the Node step host or Playwright. */
class EngineDoesNotSpawnNodeTest {
    @Test
    void engineClassFilesDoNotNameTheNodeHostOrPlaywright() throws Exception {
        Path root = Path.of("build/classes/java/main/tools/dscode/engine");
        assertTrue(Files.isDirectory(root), root.toString());
        byte[][] needles = {
                "PlaywrightNode".getBytes(StandardCharsets.UTF_8),
                "step-host.mjs".getBytes(StandardCharsets.UTF_8),
                "com/microsoft/playwright".getBytes(StandardCharsets.UTF_8),
                "com.microsoft.playwright".getBytes(StandardCharsets.UTF_8)
        };
        List<String> hits = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(path -> path.getFileName().toString().endsWith(".class")).toList()) {
                scanned++;
                byte[] bytes = Files.readAllBytes(file);
                for (byte[] needle : needles) {
                    if (indexOf(bytes, needle) >= 0) {
                        hits.add(root.relativize(file).toString().replace('\\', '/'));
                        break;
                    }
                }
            }
        }
        assertTrue(scanned > 0, "no engine class files");
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
