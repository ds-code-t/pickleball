package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.StoreCli;
import tools.dscode.store.StoreDatabase;
import tools.dscode.store.StorePaths;
import tools.dscode.store.sql.SqliteOpen;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreSqlReadOnlyTest {
    @Test
    void selectOneRunsAndAWriteIsRejected(@TempDir Path project) {
        String root = project.toAbsolutePath().toString();
        Capture select = run("store", "sql", "--query=SELECT 1", "--project=" + root);
        assertEquals(0, select.code, select.err);
        assertTrue(select.out.contains("1"), select.out);

        Capture master = run("store", "sql", "--query=SELECT name FROM sqlite_master", "--project=" + root);
        assertEquals(0, master.code, master.err);
        assertTrue(master.out.contains("sqlite_master") || master.out.contains("name"), master.out);

        Capture literal = run("store", "sql", "--query=SELECT 'DELETE' AS verb", "--project=" + root);
        assertEquals(0, literal.code, literal.err);
        assertTrue(literal.out.contains("DELETE"), literal.out);

        Capture filtered = run("store", "sql", "--query=SELECT 1 AS n", "--text=re:^1$", "--project=" + root);
        assertEquals(0, filtered.code, filtered.err);
        assertTrue(filtered.out.contains("1"), filtered.out);

        Capture exactMiss = run("store", "sql", "--query=SELECT 1 AS n", "--text=exact:nope", "--project=" + root);
        assertEquals(0, exactMiss.code, exactMiss.err);
        assertFalse(exactMiss.out.contains("nope"), exactMiss.out);

        Capture deleted = run("store", "sql", "--query=DELETE FROM files", "--project=" + root);
        assertEquals(2, deleted.code, deleted.err);
        assertTrue(deleted.err.contains("error:"), deleted.err);
        assertTrue(deleted.err.contains("fix:"), deleted.err);

        Capture multi = run("store", "sql", "--query=SELECT 1; SELECT 2", "--project=" + root);
        assertEquals(2, multi.code, multi.err);
        assertTrue(multi.err.contains("error:"), multi.err);
        assertTrue(multi.err.contains("fix:"), multi.err);
    }

    @Test
    void textFilterKeepsAMatchPastTheRowCapAndTheConnectionIsQueryOnly(@TempDir Path project) throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project);
             Statement statement = database.connection().createStatement()) {
            statement.execute("CREATE TABLE probe (v TEXT)");
            for (int index = 0; index < 200; index++) {
                statement.execute("INSERT INTO probe(v) VALUES ('no')");
            }
            statement.execute("INSERT INTO probe(v) VALUES ('needle')");
        }
        String root = project.toAbsolutePath().toString();
        Capture filtered = run("store", "sql", "--query=SELECT v FROM probe", "--text=exact:needle", "--project=" + root);
        assertEquals(0, filtered.code, filtered.err);
        assertTrue(filtered.out.contains("needle"), filtered.out);

        Capture mode = run("store", "sql", "--query=SELECT * FROM pragma_query_only", "--project=" + root);
        assertEquals(0, mode.code, mode.err);
        assertTrue(mode.out.contains("1"), mode.out);
        assertFalse(mode.out.contains("0"), mode.out);

        SQLException rejected = null;
        try (Connection connection = SqliteOpen.openReadOnly(StorePaths.mainDatabase(project));
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE probe SET v = 'smuggled' WHERE v = 'needle'");
        } catch (SQLException failure) {
            rejected = failure;
        }
        assertTrue(rejected != null && rejected.getMessage() != null
                && rejected.getMessage().toLowerCase().contains("readonly"), String.valueOf(rejected));
        Capture unchanged = run("store", "sql", "--query=SELECT v FROM probe", "--text=exact:needle", "--project=" + root);
        assertEquals(0, unchanged.code, unchanged.err);
        assertTrue(unchanged.out.contains("needle"), unchanged.out);
        Capture missing = run("store", "sql", "--query=SELECT v FROM probe", "--text=exact:smuggled", "--project=" + root);
        assertEquals(0, missing.code, missing.err);
        assertFalse(missing.out.contains("smuggled"), missing.out);
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
