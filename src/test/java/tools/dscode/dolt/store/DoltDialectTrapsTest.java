package tools.dscode.dolt.store;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One real Dolt 2.7.3 server. Each method is one dialect trap. */
class DoltDialectTrapsTest {
    @TempDir
    static Path project;

    static DoltStore store;

    @BeforeAll
    static void open() throws Exception {
        DoltStoreFixture.preferBinary();
        store = DoltStore.open(project, Binding.main());
    }

    @AfterAll
    static void shutdown() throws Exception {
        if (store != null) store.close();
        DoltStoreFixture.stop(project);
    }

    @Test
    void textKeysRoundTripAtPathWidth() throws Exception {
        String path = "k".repeat(1024);
        putFile(path, new byte[] {1});
        assertEquals(path, store.session().scalar("SELECT path FROM files WHERE path = ?", path));
    }

    @Test
    void longblobAcceptsSeventyThousandAndOneMib() throws Exception {
        byte[] seventy = new byte[70_000];
        seventy[0] = 7;
        byte[] oneMib = new byte[1_048_576];
        oneMib[100] = 9;
        putFile("blob-70k", seventy);
        putFile("blob-1m", oneMib);
        assertEquals("70000", store.session().scalar("SELECT LENGTH(raw_content) FROM files WHERE path = ?", "blob-70k"));
        assertEquals("1048576", store.session().scalar("SELECT LENGTH(raw_content) FROM files WHERE path = ?", "blob-1m"));
    }

    @Test
    void autoIncrementIdsAreNotContiguousAcrossBranches() throws Exception {
        store.session().script("CREATE TABLE IF NOT EXISTS id_probe (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(32))");
        store.session().script("CALL DOLT_ADD('id_probe')");
        store.session().script("CALL DOLT_COMMIT('-m', 'id probe')");
        long mainId = store.session().insertId("INSERT INTO id_probe(name) VALUES (?)", "main");
        store.createBranch("ids");
        long branchId;
        try (DoltStore other = DoltStore.open(project, Binding.sandbox("ids"))) {
            branchId = other.session().insertId("INSERT INTO id_probe(name) VALUES (?)", "branch");
        }
        assertTrue(mainId >= 1, "main id " + mainId);
        assertTrue(branchId > mainId, mainId + " then " + branchId);
        assertNotEquals(1L, branchId);
    }

    @Test
    void upsertReplacesTheExistingRow() throws Exception {
        store.session().update(DoltDialect.UPSERT_META, "up", "old");
        store.session().update(DoltDialect.UPSERT_META, "up", "new");
        assertEquals("new", store.session().scalar("SELECT value FROM schema_meta WHERE `key` = ?", "up"));
    }

    @Test
    void insertIgnoreKeepsTheFirstRow() throws Exception {
        store.session().update("INSERT INTO schema_meta(`key`, value) VALUES ('once', 'a')");
        store.session().update("INSERT IGNORE INTO schema_meta(`key`, value) VALUES ('once', 'b')");
        assertEquals("a", store.session().scalar("SELECT value FROM schema_meta WHERE `key` = ?", "once"));
    }

    @Test
    void informationSchemaColumnsSeesLongblob() throws Exception {
        assertEquals("raw_content", store.session().scalar("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'files' AND column_name = 'raw_content'
                """));
        assertEquals("longblob", store.session().scalar("""
                SELECT LOWER(data_type) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'files' AND column_name = 'raw_content'
                """));
    }

    @Test
    void concatTagFilterReturnsTheMatchingRowNotZero() throws Exception {
        store.session().update("""
                INSERT INTO scenarios(file_path, ord, keyword, name, line, tags, description)
                VALUES ('features/login.feature', 1, 'Scenario', 'login', 3, '@fast @login', '')
                """);
        String sql = "SELECT name FROM scenarios sc WHERE "
                + DoltDialect.taggedScenario("'features/login.feature|10'")
                + " AND " + DoltDialect.tagLike("sc.tags");
        assertEquals("login", store.session().scalar(sql, "% @fast %"));
        assertEquals("0", store.session().scalar("SELECT 'a' || 'b'"));
        assertEquals("ab", store.session().scalar("SELECT CONCAT('a', 'b')"));
    }

    @Test
    void caseInsensitiveCompareMatchesADifferentCase() throws Exception {
        store.session().update("""
                INSERT INTO runs(run_id, started_at, status, scenario, note)
                VALUES ('run-case', '2026-11-15T00:00:00Z', 'Passed', 'login', '')
                """);
        assertEquals("run-case", store.session().scalar(
                "SELECT run_id FROM runs WHERE LOWER(status) = LOWER(?)", "passed"));
    }

    @Test
    void castAsSignedReadsTheScenarioLine() throws Exception {
        assertEquals("12", store.session().scalar("""
                SELECT CAST(substr('features/a.feature|12', CHAR_LENGTH('features/a.feature') + 2) AS SIGNED)
                """));
    }

    @Test
    void instrFindsTheFeatureName() throws Exception {
        assertEquals("10", store.session().scalar("SELECT instr('features/login.feature|10', 'login.feature')"));
    }

    @Test
    void likeEscapeMatchesALiteralUnderscore() throws Exception {
        assertEquals("1", store.session().scalar("SELECT 'a_b' LIKE 'a\\\\_b' ESCAPE '\\\\'"));
        assertEquals("0", store.session().scalar("SELECT 'axb' LIKE 'a\\\\_b' ESCAPE '\\\\'"));
    }

    @Test
    void limitAndOffsetReturnTheMiddleKey() throws Exception {
        store.session().update(DoltDialect.UPSERT_META, "lim-a", "1");
        store.session().update(DoltDialect.UPSERT_META, "lim-b", "1");
        store.session().update(DoltDialect.UPSERT_META, "lim-c", "1");
        assertEquals("lim-b", store.session().scalar(
                "SELECT `key` FROM schema_meta WHERE `key` LIKE 'lim-%' ORDER BY `key` LIMIT ? OFFSET ?", 1, 1));
    }

    @Test
    void checkIdEqualsOneRejectsASecondProvenanceRow() throws Exception {
        assertThrows(SQLException.class, () ->
                store.session().update("INSERT INTO provenance(id, schema_version) VALUES (2, 1)"));
        assertEquals("1", store.session().scalar("SELECT COUNT(*) FROM provenance"));
    }

    private static void putFile(String path, byte[] raw) throws SQLException {
        store.session().update("""
                INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at,
                    last_synced_hash, db_edited, git_blob, binary_ref, byte_size)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE raw_content = VALUES(raw_content), byte_size = VALUES(byte_size)
                """, path, "h", raw, 1L, "feature", "t", "h", 0, null, 0, (long) raw.length);
    }
}
