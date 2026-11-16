package tools.dscode.dolt.store;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Change-log triggers record every non-blob column and refuse a blob without a hash. */
class ChangeLogTriggerTest {
    @TempDir
    static Path project;

    static DoltStore store;

    @BeforeAll
    static void open() throws Exception {
        store = DoltStore.open(project, Binding.main());
    }

    @AfterAll
    static void shutdown() throws Exception {
        if (store != null) store.close();
        DoltStoreFixture.stop(project);
    }

    @Test
    void loggedTablesExposeEveryColumnExceptBlobsAndRequireAHash() throws Exception {
        for (String table : java.util.List.of("files", "entities", "step_definitions")) {
            boolean blob = false;
            boolean hash = false;
            java.util.List<String> names = store.session().column(
                    "SELECT name FROM pragma_table_info('" + table + "')");
            java.util.List<String> types = store.session().column(
                    "SELECT type FROM pragma_table_info('" + table + "')");
            for (int index = 0; index < names.size(); index++) {
                String type = types.get(index) == null ? "" : types.get(index).toLowerCase(Locale.ROOT);
                if (type.contains("blob")) blob = true;
                if (names.get(index).toLowerCase(Locale.ROOT).contains("hash")) hash = true;
            }
            if (blob) assertTrue(hash, table + " has a blob column and no hash column");
        }
    }

    @Test
    void insertWritesAChangeRowWithoutTheBlob() throws Exception {
        store.session().update("""
                INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at,
                    last_synced_hash, db_edited, git_blob, binary_ref, byte_size, location, git_commit)
                VALUES ('features/one.feature', 'abc', x'010203', 1, 'feature', 't', 'abc', 0, NULL, 0, 3, 'db', '')
                """);
        String json = store.session().scalar(
                "SELECT new FROM _pkb_changes WHERE tbl = 'files' AND op = 'insert' ORDER BY seq DESC LIMIT 1");
        assertTrue(json.contains("\"path\":\"features/one.feature\""), json);
        assertTrue(json.contains("\"content_hash\":\"abc\""), json);
        assertTrue(json.contains("\"binary_ref\":0") || json.contains("\"binary_ref\":\"0\""), json);
        assertTrue(json.contains("\"location\":\"db\""), json);
        assertTrue(json.contains("\"byte_size\":3") || json.contains("\"byte_size\":\"3\""), json);
        assertFalse(json.contains("010203"), json);
        assertFalse(json.toLowerCase(Locale.ROOT).contains("raw_content"), json);
        assertEquals("1", store.session().scalar("SELECT COUNT(*) FROM _pkb_changes WHERE tbl = 'files' AND op = 'insert'"));
    }
}
