package tools.dscode.common.composite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import tools.dscode.store.SnapFindIndex;
import tools.dscode.store.StoreDatabase;
import tools.dscode.store.sql.SqlConnections;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The find index is used only when the live document version matches.
 * The real package is {@code tools.dscode.common.composite}.
 */
class SnapFindIndexTest {
    @TempDir
    Path project;

    private final Map<String, String> previous = new LinkedHashMap<>();

    @BeforeEach
    void isolate() throws Exception {
        SnapFindIndex.clearDocumentVersion();
        SnapFindIndex.resetIndexHitsForTests();
        ElementOperations.resetFindCallsForTests();
        System.clearProperty("pkb_composite");
        set("pkb_store_project", project.toAbsolutePath().normalize().toString());
        set("pkb_engine_log", "match");
        try (StoreDatabase database = StoreDatabase.open(project)) {
            try (PreparedStatement statement = database.connection().prepareStatement("""
                    INSERT INTO runs(
                        run_id, started_at, status, scenario, note, sandbox,
                        git_commit, feature_path, commit_sha, head_id, parent_commit, parent_head_id)
                    VALUES ('run-idx', '2026-11-26T01:00:00Z', 'PASSED', 'find', '', 'main',
                            '', '', '', 'current', '', '')
                    """)) {
                statement.executeUpdate();
            }
            try (PreparedStatement statement = database.connection().prepareStatement("""
                    INSERT INTO snap_find_index(
                        run_id, head_id, doc_version, identity_chain, handle, role, name, backend_node_id)
                    VALUES ('run-idx', 'current', 'v1', '[]', 'h1', 'button', 'Save', '1')
                    """)) {
                statement.executeUpdate();
            }
        }
    }

    @AfterEach
    void release() {
        SnapFindIndex.clearDocumentVersion();
        SnapFindIndex.resetIndexHitsForTests();
        ElementOperations.resetFindCallsForTests();
        System.clearProperty("pkb_composite");
        restore();
        SqlConnections.closeProject(project);
    }

    @Test
    void sameDocumentVersionUsesTheIndex() {
        SnapFindIndex.noteDocumentVersion("v1");
        ElementOperations.findElements(null, By.id("h1"));
        assertEquals(1, SnapFindIndex.indexHitsForTests());
        assertEquals(0, ElementOperations.findCallsForTests());
    }

    @Test
    void differentDocumentVersionLogsIndexMissAndFindsLive() throws Exception {
        SnapFindIndex.noteDocumentVersion("v2");
        ElementOperations.findElements(null, By.id("h1"));
        assertEquals(0, SnapFindIndex.indexHitsForTests());
        assertEquals(1, ElementOperations.findCallsForTests());
        String log = Files.readString(project.resolve(".pickleball/engine/engine.log"), StandardCharsets.UTF_8);
        assertTrue(log.contains("index miss"), log);
    }

    private void set(String key, String value) {
        previous.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private void restore() {
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (entry.getValue() == null) System.clearProperty(entry.getKey());
            else System.setProperty(entry.getKey(), entry.getValue());
        }
        previous.clear();
    }
}
