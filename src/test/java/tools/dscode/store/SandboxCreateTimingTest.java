package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlConnections;
import tools.dscode.store.sql.SqlStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prints {@code SANDBOX_CREATE_MS} for a 10 MB store and a 100 MB store. */
class SandboxCreateTimingTest {
    @TempDir
    Path project;

    @AfterEach
    void release() {
        StoreScope.clear();
        SqlConnections.closeProject(project);
    }

    @Test
    void createOfTenAndOneHundredMegabytes() throws Exception {
        byte[] chunk = new byte[1024 * 1024];
        chunk[0] = 1;
        long ten = fillAndCreate(10, "ten", chunk);
        long hundred = fillAndCreate(90, "hundred", chunk);
        System.out.println("SANDBOX_CREATE_MS 10=" + ten);
        System.out.println("SANDBOX_CREATE_MS 100=" + hundred);
        String os = System.getProperty("os.name", "").toLowerCase();
        long ceiling = os.contains("win") || os.contains("mac") ? 10_000L : 2_500L;
        assertTrue(ten < ceiling, "SANDBOX_CREATE_MS 10=" + ten + " ceiling=" + ceiling);
        assertTrue(hundred < ceiling, "SANDBOX_CREATE_MS 100=" + hundred + " ceiling=" + ceiling);
    }

    private long fillAndCreate(int megabytes, String name, byte[] chunk) throws Exception {
        try (SqlStore store = SqlStore.open(project, Binding.main())) {
            store.session().transaction("files", () -> {
                for (int index = 0; index < megabytes; index++) {
                    store.session().update(
                            """
                            INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                            "blob/" + name + "-" + index + ".bin",
                            "hash-" + name + "-" + index,
                            chunk,
                            0L,
                            "file",
                            "t",
                            "hash-" + name + "-" + index,
                            0);
                }
            });
        }
        StoreScope.clear();
        SqlConnections.closeBranch(project, Binding.main().branch());
        long started = System.nanoTime();
        try (SqlStore store = SqlStore.open(project, Binding.main())) {
            store.createBranch(name);
        }
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
