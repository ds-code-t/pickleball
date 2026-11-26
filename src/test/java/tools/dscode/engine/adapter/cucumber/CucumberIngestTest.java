package tools.dscode.engine.adapter.cucumber;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.match.PatternMatcher;
import tools.dscode.store.StoreDatabase;
import tools.dscode.store.StoreHydrator;
import tools.dscode.store.StoreScope;
import tools.dscode.store.sql.SqlConnections;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CucumberIngestTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseStoreFiles() throws Exception {
        StoreScope.clear();
        SqlConnections.closeProject(project);
    }

    @Test
    void quotedStringBecomesOneParameterAndTagsAndGlueAreApplied() throws Exception {
        write(project.resolve("src/test/java/sample/Echo.java"), """
                package sample;
                import io.cucumber.java.en.Given;
                public class Echo {
                    @Given("echo {string}")
                    public void echo(String value) {
                    }
                }
                """);
        write(project.resolve("src/test/java/other/Other.java"), """
                package other;
                import io.cucumber.java.en.Given;
                public class Other {
                    @Given("other step")
                    public void other() {
                    }
                }
                """);
        write(project.resolve("src/test/resources/features/one.feature"), """
                @feature
                Feature: One
                  @alpha @beta
                  Scenario: Kept
                    Given echo "hi"
                """);
        write(project.resolve("src/test/resources/configs/note.yaml"), "note: 1\n");
        write(project.resolve("src/test/resources/pickleball.properties"), """
                pkb_glue=sample
                pkb_callpath=src/test/resources/calls
                pkb_componentpath=src/test/resources/component
                """);

        StoreHydrator.Result result = StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        assertTrue(result.line().contains("translated="), result.line());
        assertTrue(result.line().contains("failed="), result.line());
        assertTrue(result.translated() >= 2, result.line());
        assertEquals(0, result.failed(), String.join("\n", result.notices()));

        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            String pattern = cell(connection,
                    "SELECT pattern FROM step_definitions WHERE source_path = ? AND removed = 0",
                    "src/test/java/sample/Echo.java");
            String types = cell(connection,
                    "SELECT parameter_types FROM step_definitions WHERE source_path = ? AND removed = 0",
                    "src/test/java/sample/Echo.java");
            assertEquals("[\"string\"]", types);
            assertEquals(List.of("Ava"), PatternMatcher.match(pattern, "echo \"Ava\"", types));
            assertEquals("1", cell(connection,
                    "SELECT enabled FROM step_definitions WHERE source_path = ? AND removed = 0",
                    "src/test/java/sample/Echo.java"));
            assertEquals("0", cell(connection,
                    "SELECT enabled FROM step_definitions WHERE source_path = ? AND removed = 0",
                    "src/test/java/other/Other.java"));
            List<String> tags = column(connection,
                    "SELECT tag FROM scenario_tags WHERE file_path = ? ORDER BY tag",
                    "src/test/resources/features/one.feature");
            assertEquals(List.of("@alpha", "@beta"), tags);
            assertEquals("@feature", cell(connection,
                    "SELECT tags FROM feature_docs WHERE file_path = ?",
                    "src/test/resources/features/one.feature"));
            assertEquals("0", cell(connection,
                    "SELECT enabled FROM files WHERE path = ?",
                    "src/test/resources/configs/note.yaml"));
            assertEquals("src/test/resources/calls", cell(connection,
                    "SELECT value FROM run_variables WHERE name = ?", "pkb_callpath"));
            assertEquals("src/test/resources/component", cell(connection,
                    "SELECT value FROM run_variables WHERE name = ?", "pkb_componentpath"));
        }
    }

    @Test
    void unknownParameterLeavesThePreviousRow() throws Exception {
        Path glue = project.resolve("src/test/java/sample/Echo.java");
        write(glue, """
                package sample;
                import io.cucumber.java.en.Given;
                public class Echo {
                    @Given("echo {string}")
                    public void echo(String value) {
                    }
                }
                """);
        StoreHydrator.Result first = StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        assertEquals(0, first.failed(), String.join("\n", first.notices()));
        String kept;
        try (StoreDatabase database = StoreDatabase.open(project)) {
            kept = cell(database.connection(),
                    "SELECT pattern FROM step_definitions WHERE source_path = ? AND removed = 0",
                    "src/test/java/sample/Echo.java");
        }
        SqlConnections.closeProject(project);

        write(glue, """
                package sample;
                import io.cucumber.java.en.Given;
                public class Echo {
                    @Given("echo {nope}")
                    public void echo(String value) {
                    }
                }
                """);
        StoreHydrator.Result second = StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        assertTrue(second.failed() >= 1, second.line());
        String notices = String.join("\n", second.notices());
        assertTrue(notices.contains("error:"), notices);
        assertTrue(notices.contains("fix: store glue show src/test/java/sample/Echo.java"), notices);

        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            List<String> patterns = column(connection,
                    "SELECT pattern FROM step_definitions WHERE source_path = ? AND removed = 0",
                    "src/test/java/sample/Echo.java");
            assertEquals(List.of(kept), patterns, patterns.toString());
            assertFalse(patterns.contains("echo {nope}"));
            String types = cell(connection,
                    "SELECT parameter_types FROM step_definitions WHERE source_path = ? AND removed = 0",
                    "src/test/java/sample/Echo.java");
            assertEquals(List.of("Ava"), PatternMatcher.match(kept, "echo \"Ava\"", types));
        }
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String cell(Connection connection, String sql, String... args) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) statement.setString(index + 1, args[index]);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), sql);
                return rows.getString(1);
            }
        }
    }

    private static List<String> column(Connection connection, String sql, String... args) throws Exception {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) statement.setString(index + 1, args[index]);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) values.add(rows.getString(1));
            }
        }
        return values;
    }
}
