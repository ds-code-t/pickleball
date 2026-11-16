package tools.dscode.store;

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

class StoreFreshCloneTest {
    @TempDir
    Path project;

    @Test
    void firstHydrateInstallsStartsAndCreatesTheSchema() throws Exception {
        Path feature = project.resolve("features/fresh.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, "Feature: Fresh\n", StandardCharsets.UTF_8);
        assertFalse(StorePaths.ready(project));
        Capture capture = run("store", "hydrate", "--project=" + project);
        assertEquals(0, capture.code, capture.out + capture.err);
        assertTrue(capture.out.contains("hydrated "), capture.out);
        assertTrue(capture.out.contains("database present"), capture.out);
        assertTrue(StorePaths.ready(project));
        try (StoreDatabase database = StoreDatabase.open(project)) {
            assertEquals(1, database.schemaVersion());
            assertEquals("wal", database.pragma("journal_mode"));
        }
    }

    @Test
    void storeFalseStaysFileOnly() {
        String previous = System.getProperty("pkb_store");
        try {
            System.setProperty("pkb_store", "false");
            Capture capture = run("store", "status", "--project=" + project);
            assertEquals(0, capture.code, capture.out + capture.err);
            assertTrue(capture.out.contains("enabled=false"), capture.out);
            assertTrue(capture.out.contains("database missing"), capture.out);
            assertFalse(StorePaths.ready(project));
            assertFalse(Files.exists(StorePaths.database(project)));
            assertFalse(Files.exists(project.resolve(".pickleball")));
        } finally {
            if (previous == null) System.clearProperty("pkb_store");
            else System.setProperty("pkb_store", previous);
        }
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = StoreCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
