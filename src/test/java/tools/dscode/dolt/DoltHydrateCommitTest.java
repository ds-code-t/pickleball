package tools.dscode.dolt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dolt.store.DoltDialect;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltHydrateCommitTest {
    @TempDir
    Path project;

    @Test
    void secondHydrateDoesNotCommitWhenNothingChanged() throws Exception {
        Path feature = project.resolve("features/one.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, "Feature: One\n  Scenario: A\n    * ASSERT: \"1\" equals \"1\"\n",
                StandardCharsets.UTF_8);
        DoltHydrator.Result first = DoltHydrator.hydrate(project, DoltHydrator.Options.full());
        assertEquals(0, first.conflicts());
        String commit;
        int count;
        try (DoltDatabase database = DoltDatabase.open(project)) {
            commit = DoltDialect.lastHydrateCommit(database.connection());
            count = countHydrate(database);
        }
        assertTrue(commit.startsWith("hydrate ") || commit.contains(" hydrate "), commit);
        assertFalse("none".equals(commit), commit);
        DoltHydrator.Result second = DoltHydrator.hydrate(project, DoltHydrator.Options.full());
        assertEquals(0, second.conflicts());
        try (DoltDatabase database = DoltDatabase.open(project)) {
            assertEquals(commit, DoltDialect.lastHydrateCommit(database.connection()));
            assertEquals(count, countHydrate(database));
        }
    }

    @Test
    void statusShowsTheLastHydrateCommit() throws Exception {
        Path feature = project.resolve("features/one.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, "Feature: One\n", StandardCharsets.UTF_8);
        assertEquals(0, DoltCli.execute(new String[] {"dolt", "hydrate", "--project=" + project},
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream())));
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        int code = DoltCli.execute(
                new String[] {"dolt", "status", "--project=" + project},
                new java.io.PrintStream(stdout, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        assertEquals(0, code);
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("hydrate commit "), out);
        assertTrue(out.contains("hydrate "), out);
        assertFalse(out.contains("hydrate commit none"), out);
    }

    private static int countHydrate(DoltDatabase database) throws Exception {
        try (Statement statement = database.connection().createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM dolt_log WHERE message LIKE 'hydrate %'")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }
}
