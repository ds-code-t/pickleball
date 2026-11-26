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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnabledFlagTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseStoreFiles() throws Exception {
        StoreScope.clear();
        SqlConnections.closeProject(project);
        StoreHandles.assertReleased(project);
    }

    @Test
    void outOfRootFileIsStoredDisabledAndInRootPathUsesWindowsForm() throws Exception {
        String feature = "src/test/resources/features/a.feature";
        String config = "src/test/resources/configs/note.yaml";
        write(project.resolve(feature), "Feature: A\n  Scenario: In\n    Given ready\n");
        write(project.resolve(config), "note: 1\n");
        Capture hydrate = run("store", "hydrate", "--project=" + project);
        assertEquals(0, hydrate.code, hydrate.err);

        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            assertEquals("1", cell(connection, "SELECT enabled FROM files WHERE path = ?", feature));
            assertEquals("0", cell(connection, "SELECT enabled FROM files WHERE path = ?", config));
            assertEquals("src\\test\\resources\\features\\a.feature",
                    cell(connection, "SELECT norm_path FROM files WHERE path = ?", feature));
            assertEquals("src\\test\\resources\\features",
                    cell(connection, "SELECT folder_key FROM files WHERE path = ?", feature));
        }
    }

    @Test
    void scenarioOffWritesIgnoreTagAndOnRemovesOnlyThatTag() throws Exception {
        String feature = "src/test/resources/features/one.feature";
        write(project.resolve(feature), """
                Feature: One
                  @keep
                  Scenario: Alpha
                    Given ready
                """);
        Capture hydrate = run("store", "hydrate", "--project=" + project);
        assertEquals(0, hydrate.code, hydrate.err);

        Capture off = run("store", "enable", "scenario", feature + "#1", "--off", "--project=" + project);
        assertEquals(0, off.code, off.err);
        String stored = storedText(feature);
        assertTrue(stored.contains("@keep"), stored);
        assertTrue(stored.contains("@pkb-ignore"), stored);
        assertFalse(Files.readString(project.resolve(feature), StandardCharsets.UTF_8).contains("@pkb-ignore"));

        Capture on = run("store", "enable", "scenario", feature + "#1", "--on", "--project=" + project);
        assertEquals(0, on.code, on.err);
        String restored = storedText(feature);
        assertTrue(restored.contains("@keep"), restored);
        assertFalse(restored.contains("@pkb-ignore"), restored);
    }

    @Test
    void folderOffTagsTheFeatureLineOfEachFeature() throws Exception {
        String folder = "src/test/resources/features/pack";
        String first = folder + "/a.feature";
        String second = folder + "/b.feature";
        write(project.resolve(first), "Feature: A\n  Scenario: In\n    Given ready\n");
        write(project.resolve(second), "Feature: B\n  Scenario: In\n    Given ready\n");
        Capture hydrate = run("store", "hydrate", "--project=" + project);
        assertEquals(0, hydrate.code, hydrate.err);

        Capture off = run("store", "enable", "folder", folder, "--off", "--project=" + project);
        assertEquals(0, off.code, off.err);
        assertTrue(storedText(first).contains("@pkb-ignore\nFeature: A")
                || storedText(first).contains("@pkb-ignore\r\nFeature: A"), storedText(first));
        assertTrue(storedText(second).contains("Feature: B"), storedText(second));
        assertTrue(storedText(second).contains("@pkb-ignore"), storedText(second));
        assertFalse(Files.readString(project.resolve(first), StandardCharsets.UTF_8).contains("@pkb-ignore"));
        assertFalse(Files.readString(project.resolve(second), StandardCharsets.UTF_8).contains("@pkb-ignore"));
        try (StoreDatabase database = StoreDatabase.open(project)) {
            assertEquals("0", cell(database.connection(),
                    "SELECT enabled FROM folders WHERE folder_key = ?",
                    "src\\test\\resources\\features\\pack"));
        }
    }

    private String storedText(String path) throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            StoreFiles.Row row = StoreFiles.find(database.connection(), path);
            return new String(row.raw(), StandardCharsets.UTF_8);
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
