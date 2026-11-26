package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.StoreCli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

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
