package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.source.FileScenarioSource;
import tools.dscode.store.sql.SqlConnections;
import tools.dscode.store.sql.SqliteOpen;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A tracked run freezes head id and entity hashes in runstate.db and does not copy source.db. */
class RunSnapshotTest {
    @TempDir
    Path project;

    private final Map<String, String> previous = new LinkedHashMap<>();

    @BeforeEach
    void isolateScope() {
        System.clearProperty("pkb_store_sandbox");
        StoreScope.clear();
    }

    @AfterEach
    void release() {
        restore();
        System.clearProperty("pkb_store_sandbox");
        StoreScope.clear();
        SqlConnections.closeProject(project);
        StoreExecution.finishScenario("PASSED");
        StoreScope.clear();
    }

    @Test
    void nativeRunFreezesHeadAndKeepsItAfterTheFeatureFileIsDeleted() throws Exception {
        Path feature = writeFeature();
        Capture hydrated = cli("store", "hydrate", "--project=" + project);
        assertEquals(0, hydrated.code, hydrated.out + hydrated.err);
        StoreScope.clear();
        SqlConnections.closeProject(project);
        long before = Files.size(StorePaths.mainDatabase(project));

        track("snap-main");
        RunResult result = PickleballEngine.run(new FileScenarioSource(feature), EngineOptions.of().project(project));
        assertEquals(Status.PASSED, result.status(), result.toString());
        SqlConnections.closeProject(project);
        StoreScope.clear();

        assertEquals(before, Files.size(StorePaths.mainDatabase(project)), "snapshot insert changed source.db");
        Snapshot row = snapshot("snap-main");
        assertEquals(1, row.count);
        assertEquals(headId(), row.headId, row.toString());
        assertTrue(row.parentHeadId.isBlank(), row.toString());
        assertTrue(row.entities > 0, row.toString());
        assertEquals("1", runStateMeta("run_snapshots"));
        try (StoreDatabase database = StoreDatabase.open(project)) {
            assertEquals(1, database.schemaVersion());
        }

        Files.delete(feature);
        assertTrue(Files.notExists(feature));
        Snapshot afterDelete = snapshot("snap-main");
        assertEquals(1, afterDelete.count);
        assertEquals(row.headId, afterDelete.headId);
    }

    @Test
    void cowRunStoresParentHeadAndPromoteLeavesIt() throws Exception {
        writeFeature();
        Capture hydrated = cli("store", "hydrate", "--project=" + project);
        assertEquals(0, hydrated.code, hydrated.out + hydrated.err);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "thin", "--project=" + project).code);
        StoreScope.clear();
        SqlConnections.closeProject(project);

        set("pkb_store_sandbox", "thin");
        StoreScope.clear();
        track("snap-cow");
        RunResult result = PickleballEngine.run("""
                Feature: Cow
                  Scenario: One
                    * ASSERT: "a" equals "a"
                """, EngineOptions.of().project(project));
        assertEquals(Status.PASSED, result.status(), result.toString());
        SqlConnections.closeProject(project);

        Snapshot row = snapshot("snap-cow");
        assertEquals(1, row.count, row.toString());
        assertFalse(row.parentHeadId.isBlank(), row.toString());
        clear("pkb_store_sandbox");
        StoreScope.clear();
        assertEquals(headId(), row.parentHeadId, row.toString());

        Capture promoted = cli("store", "sandbox", "promote", "thin", "--to=parent", "--logs=clear",
                "--project=" + project);
        assertEquals(0, promoted.code, promoted.out + promoted.err);
        StoreScope.clear();
        SqlConnections.closeProject(project);
        Snapshot after = snapshot("snap-cow");
        assertEquals(row.parentHeadId, after.parentHeadId, after.toString());
        assertEquals(row.headId, after.headId);
    }

    private Path writeFeature() throws Exception {
        Path feature = project.resolve("features/snap.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                Feature: Snap
                  Scenario: Kept
                    * ASSERT: "a" equals "a"
                """, StandardCharsets.UTF_8);
        return feature;
    }

    private void track(String runId) {
        set("pkb_store", "true");
        set("pkb_store_track", "true");
        set("pkb_run_id", runId);
        set("pkb_store_project", project.toAbsolutePath().normalize().toString());
    }

    private String headId() {
        Capture shown = cli("store", "head", "show", "--project=" + project);
        assertEquals(0, shown.code, shown.err);
        for (String line : shown.out.split("\\R")) {
            if (line.startsWith("head ")) return line.substring("head ".length()).trim();
        }
        throw new AssertionError(shown.out);
    }

    private Snapshot snapshot(String runId) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT head_id, parent_head_id, parent_commit,
                            (SELECT COUNT(*) FROM run_snapshot_entities WHERE run_id = ?)
                     FROM run_snapshots WHERE run_id = ?
                     """)) {
            statement.setString(1, runId);
            statement.setString(2, runId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return new Snapshot(0, "", "", "", 0);
                int entities = rows.getInt(4);
                Snapshot found = new Snapshot(1, text(rows.getString(1)), text(rows.getString(2)), text(rows.getString(3)), entities);
                assertFalse(rows.next(), "more than one snapshot row");
                return found;
            }
        }
    }

    private String runStateMeta(String key) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT value FROM schema_meta WHERE `key` = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), key);
                return rows.getString(1);
            }
        }
    }

    private Capture cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = StoreCli.execute(args, new PrintStream(out), new PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private void set(String key, String value) {
        previous.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private void clear(String key) {
        previous.putIfAbsent(key, System.getProperty(key));
        System.clearProperty(key);
    }

    private void restore() {
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (entry.getValue() == null) System.clearProperty(entry.getKey());
            else System.setProperty(entry.getKey(), entry.getValue());
        }
        previous.clear();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private record Snapshot(int count, String headId, String parentHeadId, String parentCommit, int entities) {
    }

    private record Capture(int code, String out, String err) {
    }
}
