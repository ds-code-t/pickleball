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

class JarHashResetTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseStoreFiles() throws Exception {
        StoreScope.clear();
        SqlConnections.closeProject(project);
        StoreHandles.assertReleased(project);
    }

    @Test
    void jarHashChangeRebuildsSourceRowsAndKeepsRunHistory() throws Exception {
        String feature = "src/test/resources/features/kept.feature";
        write(project.resolve(feature), "Feature: Kept\n  Scenario: Stay\n    Given ready\n");
        Capture first = run("store", "hydrate", "--project=" + project);
        assertEquals(0, first.code, first.err);

        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash)
                    VALUES ('src/test/resources/features/ghost.feature', 'abc', ?, 0, 'feature', 't', 'abc')
                    """)) {
                insert.setBytes(1, new byte[] {0});
                insert.executeUpdate();
            }
            try (PreparedStatement run = connection.prepareStatement(
                    "INSERT INTO runs(run_id, started_at, status, scenario, note) VALUES ('kept', 't', 'passed', 'A', '')")) {
                run.executeUpdate();
            }
            try (PreparedStatement hash = connection.prepareStatement(
                    "UPDATE schema_meta SET value = 'not-the-jar' WHERE `key` = 'jar_hash'")) {
                assertEquals(1, hash.executeUpdate());
            }
        }

        assertTrue(Files.isRegularFile(StorePaths.runState(project)));
        Capture second = run("store", "hydrate", "--project=" + project);
        assertEquals(0, second.code, second.err);

        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM files WHERE path = ?",
                    "src/test/resources/features/ghost.feature"));
            assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM files WHERE path = ?", feature));
            assertEquals("1", scalar(connection, "SELECT COUNT(*) FROM runs WHERE run_id = ?", "kept"));
        }
        assertTrue(Files.isRegularFile(StorePaths.runState(project)));
    }

    private static String scalar(Connection connection, String sql, String arg) throws Exception {
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
