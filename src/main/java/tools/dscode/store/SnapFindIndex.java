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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Find index in {@code runstate.db}. Consulted only when a live document version
 * was noted and that version still matches. A mismatch logs {@code index miss}.
 */
public final class SnapFindIndex {
    private static final ThreadLocal<String> LIVE = new ThreadLocal<>();
    private static final AtomicInteger INDEX_HITS = new AtomicInteger();

    private SnapFindIndex() {
    }

    public static void noteDocumentVersion(String version) {
        if (version == null || version.isBlank()) LIVE.remove();
        else LIVE.set(version);
    }

    public static void clearDocumentVersion() {
        LIVE.remove();
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
     * True when an index row for this handle has the live document version.
     * No live version, a missing run-state file, and a SQL failure are a miss
     * with no log. Rows for a different version log {@code index miss}.
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

    static String handle(By by) {
        String text = String.valueOf(by);
        int colon = text.lastIndexOf(':');
        if (colon < 0 || colon >= text.length() - 1) return text.trim();
        return text.substring(colon + 1).trim();
    }
}
