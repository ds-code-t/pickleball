package tools.dscode.store.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run-state lives in one shared file. A sandbox database does not point at it. */
class RunStateRouterTest {
    @TempDir
    static Path project;

    static SqlStore store;

    @BeforeAll
    static void open() throws Exception {
        store = SqlStore.open(project, Binding.main());
    }

    @AfterAll
    static void shutdown() throws Exception {
        if (store != null) store.close();
        StoreStoreFixture.stop(project);
    }

    @Test
    void pureRunStateIsSharedAndASandboxFileDoesNotNameIt() throws Exception {
        store.session().update("""
                INSERT INTO runs(run_id, started_at, status, scenario, note)
                VALUES ('run-1', '2026-11-16T00:00:00Z', 'PASSED', 'login', '')
                """);
        store.session().update("""
                INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at,
                    last_synced_hash, db_edited)
                VALUES ('features/login.feature', 'h', x'61', 1, 'feature', 't', 'h', 0)
                """);
        store.createBranch("box");
        Path sandbox = project.resolve(".pickleball/store/sandboxes/box/source.db");
        byte[] bytes = Files.readAllBytes(sandbox);
        String latin = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(latin.contains("runstate.db"), latin.substring(0, Math.min(latin.length(), 200)));
        assertEquals(null, store.session().scalar(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'runs'"));
        try (SqlStore other = SqlStore.open(project, Binding.sandbox("box"))) {
            assertEquals(null, other.session().scalar(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'runs'"));
            assertEquals("run-1", other.session().scalar("SELECT run_id FROM runs WHERE run_id = 'run-1'"));
            assertEquals("1", other.session().scalar("""
                    SELECT COUNT(*) FROM files f
                    JOIN runs r ON r.scenario = 'login'
                    WHERE f.path = 'features/login.feature'
                    """));
            other.session().update("""
                    INSERT INTO runs(run_id, started_at, status, scenario, note, sandbox)
                    VALUES ('run-2', '2026-11-16T00:01:00Z', 'PASSED', 'other', '', 'box')
                    """);
        }
        assertEquals("run-2", store.session().scalar("SELECT run_id FROM runs WHERE run_id = 'run-2'"));
        assertTrue(Files.isRegularFile(project.resolve(".pickleball/store/runstate.db")));
    }
}
