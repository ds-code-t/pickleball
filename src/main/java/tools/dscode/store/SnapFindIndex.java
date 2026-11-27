package tools.dscode.store;

import org.openqa.selenium.By;
import tools.dscode.engine.EngineLog;
import tools.dscode.store.sql.SqliteOpen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Find index in {@code runstate.db}. Consulted only when a live document version
 * was noted and that version still matches. A mismatch logs {@code index miss}.
 */
public final class SnapFindIndex {
    private static final ThreadLocal<String> LIVE = new ThreadLocal<>();
    private static final ThreadLocal<Path> LIVE_PROJECT = new ThreadLocal<>();
    private static final AtomicInteger INDEX_HITS = new AtomicInteger();

    private SnapFindIndex() {
    }

    public static void noteDocumentVersion(String version) {
        if (version == null || version.isBlank()) {
            clearDocumentVersion();
            return;
        }
        LIVE.set(version);
        try {
            LIVE_PROJECT.set(StoreConfig.project());
        } catch (RuntimeException failure) {
            LIVE_PROJECT.remove();
        }
    }

    /** Notes the document version for the project that published the snap, not the process cwd. */
    public static void noteFor(Path project, String version) {
        if (project == null || version == null || version.isBlank()) {
            clearDocumentVersion();
            return;
        }
        LIVE.set(version);
        LIVE_PROJECT.set(project.toAbsolutePath().normalize());
    }

    public static void clearDocumentVersion() {
        LIVE.remove();
        LIVE_PROJECT.remove();
    }

    public static String liveVersion() {
        return LIVE.get();
    }

    public static int indexHitsForTests() {
        return INDEX_HITS.get();
    }

    public static void resetIndexHitsForTests() {
        INDEX_HITS.set(0);
    }

    public static void countIndexHit() {
        INDEX_HITS.incrementAndGet();
    }

    /**
     * Writes one find-index row per published snap handle into this project's run state.
     * The short id is the handle a later {@code By.id} consults. A write failure leaves the snap result alone.
     */
    public static void indexPublished(Path project, String runId, String version, List<Map<String, Object>> rows) {
        if (project == null || runId == null || runId.isBlank() || version == null || version.isBlank() || rows == null || rows.isEmpty()) {
            return;
        }
        try (StoreDatabase database = StoreDatabase.open(project)) {
            try (PreparedStatement run = database.connection().prepareStatement("""
                    INSERT INTO runs(run_id, started_at, finished_at, status, scenario, passed, failed, note, sandbox)
                    VALUES (?, ?, NULL, 'RUNNING', '', 0, 0, '', '')
                    ON CONFLICT(run_id) DO NOTHING
                    """)) {
                run.setString(1, runId);
                run.setString(2, Instant.now().toString());
                run.executeUpdate();
            }
            try (PreparedStatement row = database.connection().prepareStatement("""
                    INSERT INTO snap_find_index(
                        run_id, head_id, doc_version, identity_chain, handle, role, name, backend_node_id)
                    VALUES (?, '', ?, '[]', ?, ?, ?, ?)
                    ON CONFLICT(run_id, handle) DO UPDATE SET
                        doc_version = excluded.doc_version,
                        identity_chain = excluded.identity_chain,
                        role = excluded.role,
                        name = excluded.name,
                        backend_node_id = excluded.backend_node_id
                    """)) {
                int written = 0;
                for (Map<String, Object> item : rows) {
                    if (item == null) continue;
                    String handle = text(item.get("id"));
                    if (handle.isBlank()) handle = text(item.get("handle"));
                    if (handle.isBlank()) continue;
                    row.setString(1, runId);
                    row.setString(2, version);
                    row.setString(3, handle);
                    row.setString(4, text(item.get("role")));
                    row.setString(5, text(item.get("name")));
                    row.setString(6, text(item.get("handle")));
                    row.addBatch();
                    written++;
                }
                if (written == 0) return;
                row.executeBatch();
            }
            noteFor(project, version);
        } catch (SQLException | RuntimeException ignored) {
            // A snap still succeeds when the find index cannot be written.
        }
    }

    /**
     * True when an index row for this handle has the live document version.
     * No live version, a missing run-state file, a project that is not the one that published
     * the snap, and a SQL failure are a miss with no log. Rows for a different version log {@code index miss}.
     */
    public static boolean consult(By by) {
        String live = LIVE.get();
        if (live == null || live.isBlank() || by == null) return false;
        Path project;
        try {
            project = StoreConfig.project();
        } catch (RuntimeException failure) {
            return false;
        }
        Path noted = LIVE_PROJECT.get();
        if (noted != null && !noted.toAbsolutePath().normalize().equals(project.toAbsolutePath().normalize())) {
            return false;
        }
        Path file = StorePaths.runState(project);
        if (!Files.isRegularFile(file)) return false;
        String handle = handle(by);
        if (handle.isBlank()) return false;
        try (Connection connection = SqliteOpen.open(file);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT doc_version FROM snap_find_index WHERE handle = ?")) {
            statement.setString(1, handle);
            boolean any = false;
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    any = true;
                    if (live.equals(rows.getString(1))) return true;
                }
            }
            if (any) EngineLog.log(project, "match", "find", "index miss");
            return false;
        } catch (SQLException ignored) {
            return false;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    static String handle(By by) {
        String text = String.valueOf(by);
        int colon = text.lastIndexOf(':');
        if (colon < 0 || colon >= text.length() - 1) return text.trim();
        return text.substring(colon + 1).trim();
    }
}
