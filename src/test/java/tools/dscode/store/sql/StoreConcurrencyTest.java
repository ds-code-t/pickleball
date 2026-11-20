package tools.dscode.store.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreConcurrencyTest {
    @TempDir
    static Path project;

    static SqlStore store;

    @BeforeAll
    static void open() throws Exception {
        StoreStoreFixture.preferBinary();
        store = SqlStore.open(project, Binding.main());
    }

    @AfterAll
    static void shutdown() throws Exception {
        if (store != null) store.close();
        StoreStoreFixture.stop(project);
    }

    @Test
    void parallelWritersOnOneBranchRetryAndSucceed() throws Exception {
        store.session().update(SqlDialect.UPSERT_META, "counter", "0");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            futures.add(pool.submit(() -> {
                try (SqlStore local = SqlStore.open(project, Binding.main())) {
                    local.session().transaction("schema_meta", () -> {
                        local.session().scalar("SELECT value FROM schema_meta WHERE `key` = 'counter'");
                        local.session().update("""
                                UPDATE schema_meta SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT)
                                WHERE `key` = 'counter'
                                """);
                    });
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
                return null;
            }));
        }
        pool.shutdown();
        assertEquals(true, pool.awaitTermination(120, TimeUnit.SECONDS));
        for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        assertEquals(Integer.toString(threads),
                store.session().scalar("SELECT value FROM schema_meta WHERE `key` = 'counter'"));
    }

    @Test
    void aDatabaseFileLockIsRetried() throws Exception {
        SQLException locked = new SQLException("[SQLITE_BUSY] The database file is locked");
        assertTrue(SqlDialect.conflict(locked), locked.getMessage());
        int[] runs = {0};
        SqlDialect.retry("rows", () -> {
            runs[0]++;
            if (runs[0] == 1) throw locked;
        });
        assertEquals(2, runs[0]);
    }

    @Test
    void aForcedConflictIsRetriedThenSucceeds() throws Exception {
        SqlDialect.forcedConflictRemaining = 1;
        try {
            store.session().transaction("schema_meta", () ->
                    store.session().update(SqlDialect.UPSERT_META, "forced", "ok"));
        } finally {
            SqlDialect.forcedConflictRemaining = 0;
        }
        assertEquals("ok", store.session().scalar("SELECT value FROM schema_meta WHERE `key` = 'forced'"));
    }

    @Test
    void twentyBranchesSeeOnlyTheirOwnRows() throws Exception {
        for (int index = 0; index < 20; index++) {
            String name = "n" + index;
            String path = "branch-" + index + ".feature";
            store.createBranch(name);
            try (SqlStore other = SqlStore.open(project, Binding.sandbox(name))) {
                other.session().update("""
                        INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at,
                            last_synced_hash, db_edited, git_blob, binary_ref, byte_size)
                        VALUES (?, 'h', ?, 1, 'feature', 't', 'h', 0, NULL, 0, 1)
                        """, path, new byte[] {1});
                assertEquals("1", other.session().scalar("SELECT COUNT(*) FROM files WHERE path = ?", path));
                assertEquals("0", other.session().scalar(
                        "SELECT COUNT(*) FROM files WHERE path LIKE 'branch-%' AND path <> ?", path), name);
            }
        }
        assertEquals("0", store.session().scalar("SELECT COUNT(*) FROM files WHERE path LIKE 'branch-%'"));
    }
}
