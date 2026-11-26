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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioTagTableTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseStoreFiles() throws Exception {
        StoreScope.clear();
        SqlConnections.closeProject(project);
        StoreHandles.assertReleased(project);
    }

    @Test
    void scenarioTagAndExamplesTagStayOnThatScenario() throws Exception {
        String feature = "src/test/resources/features/tags.feature";
        write(project.resolve(feature), """
                Feature: Tags
                  @scenario
                  Scenario: One
                    Given ready

                  @outline
                  Scenario Outline: Two
                    Given <item>
                    @examples
                    Examples:
                      | item |
                      | a |

                  Scenario: Three
                    Given other
                """);
        Capture hydrate = run("store", "hydrate", "--project=" + project);
        assertEquals(0, hydrate.code, hydrate.err);

        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            assertEquals(List.of("1"), ords(connection, feature, "@scenario"));
            assertEquals(List.of("2"), ords(connection, feature, "@outline"));
            assertEquals(List.of("2"), ords(connection, feature, "@examples"));
            assertEquals("0", count(connection, feature, 3));
            assertTrue(cell(connection,
                    "SELECT tags FROM scenarios WHERE file_path = ? AND ord = 1", feature).contains("@scenario"));
        }
        String query = StoreTrack.query(project, new StoreTrack.Filter(
                null, null, null, null, null, null, null, null, 20, 0,
                null, "@examples", null, null, null, false), false);
        assertEquals("events 0\n", query);
    }

    private static List<String> ords(Connection connection, String path, String tag) throws Exception {
        List<String> ords = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT scenario_ord FROM scenario_tags WHERE file_path = ? AND tag = ? ORDER BY scenario_ord")) {
            statement.setString(1, path);
            statement.setString(2, tag);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) ords.add(rows.getString(1));
            }
        }
        return ords;
    }

    private static String count(Connection connection, String path, int ord) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM scenario_tags WHERE file_path = ? AND scenario_ord = ?")) {
            statement.setInt(2, ord);
            statement.setString(1, path);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
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
