package tools.dscode.docs;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Current guides describe the R8 fix-back 2 behavior that the code delivers.
 */
class SpineR8FixBack2DocsTest {
    @Test
    void guidesDescribeTheDeliveredFixBack2Behavior() throws Exception {
        String configuration = text("docs/configuration.md");
        String agents = text("AGENTS.md");
        String playwright = text("docs/playwright.md");
        String notes = text("docs/release-notes.md");
        String map = text("docs/agent/feature-map.md");
        String debt = text("docs/tech-debt.md");

        assertTrue(configuration.contains("java @argfile"), configuration);
        assertTrue(configuration.contains("source-hashes.txt"), configuration);
        assertTrue(configuration.contains("stopped the warm JVM"), configuration);
        assertTrue(configuration.contains("bidi intercept off --id="), configuration);
        assertTrue(configuration.contains("read back from that cache"), configuration);
        assertTrue(configuration.contains("window not found"), configuration);
        assertTrue(configuration.contains("releases the claim on quit"), configuration);

        assertTrue(agents.contains("java @argfile"), agents);
        assertTrue(agents.contains("source-hashes.txt"), agents);
        assertTrue(agents.contains("stopped the warm JVM"), agents);
        assertTrue(agents.contains("bidi intercept off --id="), agents);
        assertTrue(agents.contains("releases the claim on quit"), agents);

        assertTrue(playwright.contains("bidi intercept off --id="), playwright);
        assertTrue(playwright.contains("read back from that cache"), playwright);

        assertTrue(notes.contains("Spine R8 fix-back 1."), notes);
        assertTrue(notes.contains("Spine R8 fix-back 2."), notes);
        assertTrue(notes.contains("source-hashes.txt"), notes);

        assertTrue(map.contains("source-hashes.txt"), map);
        assertTrue(map.contains("bidi intercept off --id="), map);

        assertFalse(debt.contains("## Spine R8 fix-back 2 (open"), debt);
    }

    private static String text(String relative) throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) throw new IllegalStateException("settings.gradle not found");
        return Files.readString(dir.resolve(relative), StandardCharsets.UTF_8);
    }
}
