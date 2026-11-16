package tools.dscode.dolt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltFreshCloneTest {
    @TempDir
    Path project;

    @Test
    void firstHydrateInstallsStartsAndCreatesTheSchema() throws Exception {
        Path feature = project.resolve("features/fresh.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, "Feature: Fresh\n", StandardCharsets.UTF_8);
        assertFalse(DoltPaths.ready(project));
        Capture capture = run("dolt", "hydrate", "--project=" + project);
        assertEquals(0, capture.code, capture.out + capture.err);
        assertTrue(capture.out.contains("hydrated "), capture.out);
        assertTrue(capture.out.contains("database present"), capture.out);
        assertTrue(DoltPaths.ready(project));
        try (DoltDatabase database = DoltDatabase.open(project)) {
            assertEquals(1, database.schemaVersion());
            assertEquals("wal", database.pragma("journal_mode"));
        }
    }

    @Test
    void doltFalseStaysFileOnly() {
        String previous = System.getProperty("pkb_dolt");
        try {
            System.setProperty("pkb_dolt", "false");
            Capture capture = run("dolt", "status", "--project=" + project);
            assertEquals(0, capture.code, capture.out + capture.err);
            assertTrue(capture.out.contains("enabled=false"), capture.out);
            assertTrue(capture.out.contains("database missing"), capture.out);
            assertFalse(DoltPaths.ready(project));
            assertFalse(Files.exists(DoltPaths.database(project)));
            assertFalse(Files.exists(project.resolve(".pickleball")));
        } finally {
            if (previous == null) System.clearProperty("pkb_dolt");
            else System.setProperty("pkb_dolt", previous);
        }
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DoltCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
