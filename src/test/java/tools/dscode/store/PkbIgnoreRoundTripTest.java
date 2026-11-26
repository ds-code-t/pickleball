package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.dscode.store.sql.SqlConnections;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PkbIgnoreRoundTripTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseStoreFiles() throws Exception {
        StoreScope.clear();
        SqlConnections.closeProject(project);
        StoreHandles.assertReleased(project);
    }

    @Test
    void extractThenHydrateKeepsTheScenarioDisabled() throws Exception {
        String feature = "src/test/resources/features/round.feature";
        write(project.resolve(feature), """
                Feature: Round
                  @keep
                  Scenario: Stay
                    Given ready
                """);
        Capture hydrate = run("store", "hydrate", "--project=" + project);
        assertEquals(0, hydrate.code, hydrate.err);
        Capture off = run("store", "enable", "scenario", feature + "#1", "--off", "--project=" + project);
        assertEquals(0, off.code, off.err);
        Capture extract = run("store", "extract", "--project=" + project);
        assertEquals(0, extract.code, extract.err);
        String disk = Files.readString(project.resolve(feature), StandardCharsets.UTF_8);
        assertTrue(disk.contains("@pkb-ignore"), disk);
        assertTrue(disk.contains("@keep"), disk);

        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE scenarios SET enabled = 1 WHERE file_path = ?")) {
                update.setString(1, feature);
                assertEquals(1, update.executeUpdate());
            }
        }
        Capture again = run("store", "hydrate", "--project=" + project);
        assertEquals(0, again.code, again.err);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            assertEquals("0", cell(database.connection(),
                    "SELECT enabled FROM scenarios WHERE file_path = ? AND ord = 1", feature));
        }
    }

    private static String cell(Connection connection, String sql, String arg) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, arg);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = StoreCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
