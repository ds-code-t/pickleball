package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonEditTest {
    private static final String PATH = "features/pay.feature";
    private static final String ORIGINAL = """
            Feature: Pay
              Scenario: One
                Given ready
            """;
    private static final String EDITED = """
            @billing
            Feature: Pay
              @fast
              Scenario: One
                Given ready
              Scenario: Two
                When the amount is 10
                  | field | value |
                  | name | Ada |
                Then stored
                  \"\"\"
                  receipt
                  \"\"\"
            """;

    @TempDir
    Path project;

    @Test
    void featurePutValidatesReprojectsAndLeavesTheSyncHash() throws Exception {
        write(PATH, ORIGINAL);
        assertEquals(0, run("dalton", "hydrate", "--project=" + project).code);
        DaltonFiles.Row before = row(PATH);
        String synced = before.lastSyncedHash();

        Capture put = run("dalton", "feature", "put", "features\\pay.feature",
                "--text=" + EDITED, "--project=" + project);
        assertEquals(0, put.code, put.err);
        assertTrue(put.out.contains("feature features/pay.feature edited=1"), put.out);
        assertEquals(ORIGINAL, Files.readString(project.resolve(PATH)));

        DaltonFiles.Row after = row(PATH);
        assertTrue(after.edited());
        assertEquals(synced, after.lastSyncedHash());
        assertEquals(DaltonHashes.sha256(EDITED.getBytes(StandardCharsets.UTF_8)), after.contentHash());
        assertArrayEquals(EDITED.getBytes(StandardCharsets.UTF_8), after.raw());
        assertEquals(List.of("One", "Two"), scenarioNames());
        assertEquals("@fast", scenarioTags().get(0));
        assertTrue(stepText().contains("the amount is 10"), stepText().toString());
        assertTrue(docstrings().contains("receipt"), docstrings().toString());
        assertTrue(tables().stream().anyMatch(table -> table != null && table.contains("Ada")), tables().toString());

        Capture again = run("dalton", "hydrate", "--project=" + project);
        assertEquals(0, again.code, again.err);
        assertTrue(again.out.contains("conflicts=0"), again.out);
        assertArrayEquals(EDITED.getBytes(StandardCharsets.UTF_8), row(PATH).raw());
        assertTrue(row(PATH).edited());

        Files.writeString(project.resolve(PATH), ORIGINAL.replace("One", "File"), StandardCharsets.UTF_8);
        Capture conflict = run("dalton", "hydrate", "--project=" + project);
        assertEquals(1, conflict.code, conflict.out);
        assertTrue(conflict.err.contains("conflict: " + PATH), conflict.err);
        assertArrayEquals(EDITED.getBytes(StandardCharsets.UTF_8), row(PATH).raw());

        Capture forced = run("dalton", "hydrate", "--force", "--project=" + project);
        assertEquals(0, forced.code, forced.err);
        assertTrue(forced.out.contains("updated=1"), forced.out);
        assertFalse(row(PATH).edited());
        assertTrue(new String(row(PATH).raw(), StandardCharsets.UTF_8).contains("File"));
    }

    @Test
    void badGherkinRollsBackAndStructuredEditsAreRejected() throws Exception {
        write(PATH, ORIGINAL);
        assertEquals(0, run("dalton", "hydrate", "--project=" + project).code);
        Capture good = run("dalton", "feature", "put", PATH, "--text=" + EDITED, "--project=" + project);
        assertEquals(0, good.code, good.err);
        String hash = row(PATH).contentHash();

        Path replacement = project.resolve("replacement.feature");
        Files.writeString(replacement, "this is not gherkin\n", StandardCharsets.UTF_8);
        Capture bad = run("dalton", "feature", "put", PATH, "--file=" + replacement, "--project=" + project);
        assertEquals(1, bad.code);
        assertTrue(bad.err.contains("Gherkin is not valid: " + PATH), bad.err);
        assertEquals(hash, row(PATH).contentHash());
        assertEquals(List.of("One", "Two"), scenarioNames());
        assertTrue(row(PATH).edited());

        Capture scenario = run("dalton", "scenario", "put", PATH, "--project=" + project);
        assertEquals(1, scenario.code);
        assertTrue(scenario.err.contains("Structured edits are not supported"), scenario.err);
        Capture step = run("dalton", "step", "update", "--project=" + project);
        assertEquals(1, step.code);
        assertTrue(step.err.contains("cannot be updated directly"), step.err);
        Capture nested = run("dalton", "feature", "scenario", "--project=" + project);
        assertEquals(1, nested.code);
        assertTrue(nested.err.contains("Structured edits are not supported"), nested.err);
        assertEquals(hash, row(PATH).contentHash());
    }

    @Test
    void untrackedDatabaseEditIsAConflictUntilForced() throws Exception {
        write(PATH, ORIGINAL);
        assertEquals(0, run("dalton", "hydrate", "--project=" + project).code);
        byte[] original = Files.readAllBytes(project.resolve(PATH));
        try (Connection connection = DriverManager.getConnection(DaltonDatabase.jdbcUrl(DaltonPaths.database(project)));
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE files SET content_hash = 'untracked', raw_content = X'616263' WHERE path = '" + PATH + "'");
        }
        Capture hydrate = run("dalton", "hydrate", "--project=" + project);
        assertEquals(1, hydrate.code, hydrate.out);
        assertTrue(hydrate.err.contains("untracked DB edit on " + PATH), hydrate.err);
        assertTrue(hydrate.err.contains("conflict: " + PATH), hydrate.err);
        assertEquals("untracked", row(PATH).contentHash());
        assertArrayEquals(new byte[] {'a', 'b', 'c'}, row(PATH).raw());
        assertFalse(row(PATH).edited());

        Capture forced = run("dalton", "hydrate", "--force", "--project=" + project);
        assertEquals(0, forced.code, forced.err);
        assertFalse(forced.err.contains("untracked DB edit"), forced.err);
        assertArrayEquals(original, row(PATH).raw());
        assertEquals(DaltonHashes.sha256(original), row(PATH).contentHash());
        assertFalse(row(PATH).edited());
    }

    @Test
    void entityPutUsesTheSameEditedFlags() throws Exception {
        String original = """
                # keep
                type: Account
                id: ada
                version: 1
                payload: {"name":"Ada","password":"<password:qaDb>"}
                """;
        Path file = project.resolve("dalton-entities/Account/ada.entity");
        Files.createDirectories(file.getParent());
        Files.writeString(file, original, StandardCharsets.UTF_8);
        assertEquals(0, run("dalton", "hydrate", "--project=" + project).code);
        String synced = row("dalton-entities/Account/ada.entity").lastSyncedHash();
        assertFalse(row("dalton-entities/Account/ada.entity").edited());

        Capture put = run("dalton", "entity", "put", "Account", "ada", "--version=2",
                "--payload={\"name\":\"Ada\",\"password\":\"<password:qaDb>\"}", "--project=" + project);
        assertEquals(0, put.code, put.err);
        DaltonFiles.Row after = row("dalton-entities/Account/ada.entity");
        assertTrue(after.edited());
        assertEquals(synced, after.lastSyncedHash());
        assertEquals(2, DaltonEntities.get(project, "Account", "ada").version());
        assertTrue(after.contentHash().equals(DaltonHashes.sha256(after.raw())));
        assertEquals(original, Files.readString(file));
    }

    @Test
    void featurePutLoggingIsToggleable() throws Exception {
        write(PATH, ORIGINAL);
        String previous = System.getProperty("pkb_dalton_log");
        try {
            System.setProperty("pkb_dalton_log", "feature");
            Capture put = run("dalton", "feature", "put", PATH, "--text=" + ORIGINAL, "--project=" + project);
            assertEquals(0, put.code, put.err);
            String text = Files.readString(DaltonPaths.log(project), StandardCharsets.UTF_8);
            assertTrue(text.contains(" feature " + PATH + " put edited=1"), text);
            assertTrue(text.charAt(0) == '2', text);
        } finally {
            if (previous == null) System.clearProperty("pkb_dalton_log");
            else System.setProperty("pkb_dalton_log", previous);
        }
    }

    private List<String> scenarioNames() throws Exception {
        return column("SELECT name FROM scenarios WHERE file_path = '" + PATH + "' ORDER BY ord");
    }

    private List<String> scenarioTags() throws Exception {
        return column("SELECT tags FROM scenarios WHERE file_path = '" + PATH + "' ORDER BY ord");
    }

    private List<String> stepText() throws Exception {
        return column("SELECT text FROM steps WHERE file_path = '" + PATH + "' ORDER BY scenario_ord, ord");
    }

    private List<String> docstrings() throws Exception {
        return column("SELECT docstring FROM steps WHERE file_path = '" + PATH + "' ORDER BY scenario_ord, ord");
    }

    private List<String> tables() throws Exception {
        return column("SELECT table_json FROM steps WHERE file_path = '" + PATH + "' ORDER BY scenario_ord, ord");
    }

    private List<String> column(String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(DaltonDatabase.jdbcUrl(DaltonPaths.database(project)));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }
        return values;
    }

    private DaltonFiles.Row row(String path) throws Exception {
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            return DaltonFiles.find(database.connection(), path);
        }
    }

    private void write(String relative, String text) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DaltonCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
