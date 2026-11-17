package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlConnections;
import tools.dscode.store.sql.SqlStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prints {@code SANDBOX_MERGE_MS} for 1,000 non-feature rows. Ceiling is about ten times the 2s target. */
class SandboxMergeTimingTest {
    @TempDir
    Path project;

    @AfterEach
    void release() throws Exception {
        StoreScope.clear();
        SqlConnections.closeProject(project);
        StoreTestProcesses.release(project);
    }

    @Test
    void mergeOfOneThousandRows() throws Exception {
        try (SqlStore store = SqlStore.open(project, Binding.main())) {
            store.createBranch("bulk");
        }
        try (SqlStore store = SqlStore.open(project, Binding.sandbox("bulk"))) {
            store.session().transaction("files", () -> {
                for (int index = 0; index < 1000; index++) {
                    store.session().update(
                            """
                            INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                            "notes/row-" + index + ".txt",
                            "hash-" + index,
                            ("row-" + index).getBytes(StandardCharsets.UTF_8),
                            0L,
                            "file",
                            "t",
                            "",
                            1);
                }
            });
        }
        StoreScope.clear();
        SqlConnections.closeProject(project);
        long started = System.nanoTime();
        Capture merged = cli("store", "sandbox", "merge", "bulk", "--json", "--project=" + project);
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("SANDBOX_MERGE_MS=" + elapsed);
        assertEquals(0, merged.code, merged.err + merged.out);
        assertTrue(merged.out.contains("\"applied\":1000"), merged.out);
        assertTrue(elapsed < 20_000L, "SANDBOX_MERGE_MS=" + elapsed);
    }

    private Capture cli(String... args) {
        StoreScope.clear();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = StoreCli.execute(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
