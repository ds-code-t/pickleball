package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlConnections;
import tools.dscode.store.sql.SqlStore;
import tools.dscode.store.sql.SqliteOpen;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A copy-on-write sandbox stores the edit in the child head and leaves the parent file row. */
class CowSandboxTest {
    @TempDir
    Path project;

    @AfterEach
    void release() {
        StoreScope.clear();
        SqlConnections.closeProject(project);
    }

    @Test
    void editLeavesTheParentRowShowsInTheChildHeadAndStaysSmallerThanACopy() throws Exception {
        Files.createDirectories(project.resolve("features"));
        String original = """
                Feature: Pay
                  Scenario: Ada
                    * INFO: amount is 1
                """;
        String other = """
                Feature: Other
                  Scenario: Bea
                    * INFO: amount is 2
                """;
        Files.writeString(project.resolve("features/pay.feature"), original);
        Files.writeString(project.resolve("features/other.feature"), other);
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        String bulk = "b".repeat(256 * 1024);
        assertEquals(0, cli("store", "file", "put", "--path=notes/a.txt", "--text=" + bulk,
                "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "file", "put", "--path=notes/b.txt", "--text=" + bulk,
                "--project=" + project).code);
        StoreScope.clear();

        assertEquals(0, cli("store", "sandbox", "create", "thin", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "full", "--mode=copy", "--project=" + project).code);
        StoreScope.clear();
        SqlConnections.closeProject(project);
        long thinSize = Files.size(StorePaths.sandboxDatabase(project, "thin"));
        long fullSize = Files.size(StorePaths.sandboxDatabase(project, "full"));
        assertTrue(thinSize + 100_000 < fullSize, "cow " + thinSize + " copy " + fullSize);

        String edited = "Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 9\n";
        Capture put = cli("store", "feature", "put", "features/pay.feature", "--text=" + edited,
                "--sandbox=thin", "--project=" + project);
        assertEquals(0, put.code, put.err);
        StoreScope.clear();
        Capture deleted = cli("store", "file", "delete", "--path=features/other.feature", "--confirm",
                "--sandbox=thin", "--project=" + project);
        assertEquals(0, deleted.code, deleted.err);
        StoreScope.clear();
        SqlConnections.closeProject(project);

        assertEquals(original, Files.readString(project.resolve("features/pay.feature")));
        assertEquals(other, Files.readString(project.resolve("features/other.feature")));
        try (Connection main = SqliteOpen.open(StorePaths.mainDatabase(project));
             Statement statement = main.createStatement()) {
            try (ResultSet pay = statement.executeQuery(
                    "SELECT CAST(raw_content AS TEXT) FROM files WHERE path = 'features/pay.feature'")) {
                assertTrue(pay.next());
                assertEquals(original, pay.getString(1));
            }
            try (ResultSet kept = statement.executeQuery(
                    "SELECT CAST(raw_content AS TEXT) FROM files WHERE path = 'features/other.feature'")) {
                assertTrue(kept.next());
                assertEquals(other, kept.getString(1));
            }
        }
        try (SqlStore child = SqlStore.open(project, Binding.sandbox("thin"))) {
            Connection connection = child.session().connection();
            assertEquals("cow", UniversalEntity.meta(connection, "sandbox_mode"));
            assertEquals("main", UniversalEntity.text(connection,
                    "SELECT value FROM _pkb_branch WHERE `key` = 'parent'"));
            try (Statement statement = connection.createStatement();
                 ResultSet head = statement.executeQuery("""
                         SELECT payload_json, tombstone FROM head_rows
                         WHERE head_id = 'current' AND tbl = 'files' AND pk = 'features/pay.feature'
                         """)) {
                assertTrue(head.next(), "child head has no edit");
                assertTrue(head.getString(1).contains("amount is 9"), head.getString(1));
                assertEquals(0, head.getInt(2));
            }
            UniversalEntity.View hidden = UniversalEntity.resolve(connection, "files", "features/other.feature");
            assertTrue(hidden.tombstone(), "deleted row is not a tombstone");
            assertFalse(hidden.present());
        }
    }

    @Test
    void autoHydrateKeepsAChildFeaturePut() throws Exception {
        Files.createDirectories(project.resolve("features"));
        String original = """
                Feature: Pay
                  Scenario: Ada
                    * INFO: amount is 1
                """;
        Files.writeString(project.resolve("features/pay.feature"), original);
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "thin", "--project=" + project).code);
        StoreScope.clear();
        String edited = """
                Feature: Pay
                  @store-side
                  Scenario: Left pay
                    * INFO: amount is 8
                """;
        Capture put = cli("store", "feature", "put", "features/pay.feature", "--text=" + edited,
                "--sandbox=thin", "--project=" + project);
        assertEquals(0, put.code, put.err);
        StoreScope.clear();
        String previousStore = System.getProperty("pkb_store");
        String previousProject = System.getProperty("pkb_store_project");
        String previousSandbox = System.getProperty("pkb_store_sandbox");
        try {
            System.setProperty("pkb_store", "true");
            System.setProperty("pkb_store_project", project.toString());
            System.setProperty("pkb_store_sandbox", "thin");
            StoreScope.clear();
            StoreRuntime.reset();
            StoreRuntime.beforeFeatureLoad();
            byte[] bytes = StoreFeatureSource.bytesFor(project.resolve("features/pay.feature").toUri());
            assertTrue(bytes != null, "feature put is missing from the child");
            String text = new String(bytes, StandardCharsets.UTF_8);
            assertTrue(text.contains("@store-side"), text);
            assertTrue(text.contains("Left pay"), text);
            assertEquals(original, Files.readString(project.resolve("features/pay.feature")));
        } finally {
            StoreRuntime.reset();
            StoreScope.clear();
            restore("pkb_store", previousStore);
            restore("pkb_store_project", previousProject);
            restore("pkb_store_sandbox", previousSandbox);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private Capture cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = StoreCli.execute(args, new PrintStream(out), new PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
