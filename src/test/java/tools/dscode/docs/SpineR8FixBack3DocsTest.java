package tools.dscode.docs;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Current guides describe the R8 fix-back 3 behavior that the code delivers.
 */
class SpineR8FixBack3DocsTest {
    @Test
    void guidesDescribeTheDeliveredFixBack3Behavior() throws Exception {
        String configuration = text("docs/configuration.md");
        String agents = text("AGENTS.md");
        String playwright = text("docs/playwright.md");
        String notes = text("docs/release-notes.md");
        String map = text("docs/agent/feature-map.md");
        String debt = text("docs/tech-debt.md");

        assertTrue(configuration.contains("one glue instance set"),
                "docs/configuration.md missing: one glue instance set");
        assertTrue(configuration.contains("An incremental `JavaCompiler` compile keeps the store worker"),
                "docs/configuration.md missing: incremental compile keeps the store worker");
        assertTrue(configuration.contains("A single-window close keeps the claim"),
                "docs/configuration.md missing: single-window close keeps the claim");
        assertTrue(configuration.contains("`downloaded=`"),
                "docs/configuration.md missing: downloaded=");
        assertTrue(configuration.contains("Later single-file changes use `JavaCompiler` and do not spawn Maven or Gradle."),
                "docs/configuration.md missing: later single-file JavaCompiler sentence");
        assertFalse(configuration.contains("releases the claim on quit or close"),
                "docs/configuration.md still says quit or close");
        assertFalse(configuration.contains("The child runs that step"),
                "docs/configuration.md still describes a per-step child");

        assertTrue(agents.contains("one glue instance set"), "AGENTS.md missing: one glue instance set");
        assertTrue(agents.contains("An incremental `JavaCompiler` compile keeps the store worker"),
                "AGENTS.md missing: incremental compile keeps the store worker");
        assertTrue(agents.contains("A single-window close keeps the claim"),
                "AGENTS.md missing: single-window close keeps the claim");
        assertTrue(agents.contains("Later single-file changes use `JavaCompiler` and do not spawn Maven or Gradle."),
                "AGENTS.md missing: later single-file JavaCompiler sentence");
        assertFalse(agents.contains("releases the claim on quit or close"), "AGENTS.md still says quit or close");
        assertFalse(agents.contains("The child runs that step"), "AGENTS.md still describes a per-step child");

        assertTrue(playwright.contains("A single-window close keeps the claim"),
                "docs/playwright.md missing: single-window close keeps the claim");
        assertTrue(playwright.contains("`downloaded=`"), "docs/playwright.md missing: downloaded=");
        assertTrue(playwright.contains("read back from that cache"), playwright);
        assertFalse(playwright.contains("`close` releases the claim."),
                "docs/playwright.md still says close releases the claim");

        assertTrue(notes.contains("Spine R8 fix-back 3."), notes);
        assertTrue(notes.contains("Spine R8 fix-back 1."), notes);
        assertTrue(notes.contains("Spine R8 fix-back 2."), notes);
        assertTrue(notes.contains("one glue instance set"), notes);
        assertTrue(notes.contains("keeps the store worker"), notes);

        assertTrue(map.contains("one child scenario"), "docs/agent/feature-map.md missing: one child scenario");
        assertTrue(map.contains("keeps the store worker"), "docs/agent/feature-map.md missing: keeps the store worker");
        assertTrue(map.contains("`downloaded=`"), "docs/agent/feature-map.md missing: downloaded=");
        assertTrue(map.contains("A single-window close keeps the claim")
                || map.contains("a single-window close keeps the claim"),
                "docs/agent/feature-map.md missing: single-window close keeps the claim");
        assertFalse(map.contains("releases the claim on quit or close"),
                "docs/agent/feature-map.md still says quit or close");

        assertFalse(debt.contains("## Spine R8 fix-back 3 (open"), debt);
        assertTrue(debt.contains("SpineR8FixBack3DocsTest"), debt);
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
