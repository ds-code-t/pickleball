package tools.dscode.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.SqlDialect;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replaces {@code StoreHydrateCommitTest}: generations, not server commits. */
class StoreGenerationTest {
    @TempDir
    Path project;

    @Test
    void secondHydrateDoesNotBumpGenerationWhenNothingChanged() throws Exception {
        Path feature = project.resolve("features/one.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, "Feature: One\n  Scenario: A\n    * ASSERT: \"1\" equals \"1\"\n",
                StandardCharsets.UTF_8);
        StoreHydrator.Result first = StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        assertEquals(0, first.conflicts());
        String label;
        int generation;
        try (StoreDatabase database = StoreDatabase.open(project)) {
            label = SqlDialect.lastHydrateCommit(database.connection());
            generation = generation(database);
        }
        assertTrue(label.matches("[1-9][0-9]* \\S.*"), label);
        assertFalse(label.contains("hydrate commit"), label);
        StoreHydrator.Result second = StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        assertEquals(0, second.conflicts());
        assertEquals(0, second.inserted());
        assertEquals(0, second.updated());
        try (StoreDatabase database = StoreDatabase.open(project)) {
            assertEquals(label, SqlDialect.lastHydrateCommit(database.connection()));
            assertEquals(generation, generation(database));
        }
    }

    @Test
    void statusShowsTheHydrateGeneration() throws Exception {
        Path feature = project.resolve("features/one.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, "Feature: One\n", StandardCharsets.UTF_8);
        assertEquals(0, StoreCli.execute(new String[] {"store", "hydrate", "--project=" + project},
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream())));
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        int code = StoreCli.execute(
                new String[] {"store", "status", "--project=" + project},
                new java.io.PrintStream(stdout, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        assertEquals(0, code);
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("store=embedded"), out);
        assertTrue(out.contains("hydrate generation "), out);
        assertFalse(out.contains("hydrate commit"), out);
        assertFalse(out.contains("hydrate generation none"), out);
        assertFalse(out.contains("hydrate generation 0 "), out);
        assertFalse(out.contains("hydrate generation unavailable"), out);
    }

    private static int generation(StoreDatabase database) throws Exception {
        try (Statement statement = database.connection().createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT CAST(value AS INTEGER) FROM schema_meta WHERE `key` = 'hydrate_generation'")) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }
}
