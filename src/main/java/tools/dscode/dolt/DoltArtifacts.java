package tools.dscode.dolt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Links retained screenshot files to a run. The files stay where the diagnostic
 * report wrote them. This class does not delete or rewrite those files.
 */
public final class DoltArtifacts {
    private DoltArtifacts() {
    }

    public record Artifact(String id, String runId, String stepExecId, String kind, String path, String retainedAt) {
    }

    /** Inserts screenshot rows for images under a retained diagnostic directory. */
    public static int linkRetained(Path project, String runId, String relative, String stepExecId) throws IOException, SQLException {
        if (project == null || runId == null || runId.isBlank() || relative == null || relative.isBlank()) return 0;
        String path = DoltPaths.normalizeRelative(relative);
        Path file = project.resolve(path);
        Path directory = Files.isDirectory(file) ? file : file.getParent();
        if (directory == null || !Files.isDirectory(directory)) return 0;
        int linked = 0;
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path item : walk.filter(Files::isRegularFile).toList()) {
                if (!image(item.getFileName().toString())) continue;
                if (link(project, runId, item, stepExecId)) linked++;
            }
        }
        return linked;
    }

    public static boolean link(Path project, String runId, Path file, String stepExecId) throws SQLException, IOException {
        if (project == null || file == null || !Files.isRegularFile(file)) return false;
        Path root = project.toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) return false;
        String relative = root.relativize(absolute).toString().replace('\\', '/');
        String id = DoltHashes.sha256((runId + "|" + relative).getBytes(StandardCharsets.UTF_8));
        String now = Instant.now().toString();
        try (DoltDatabase database = DoltDatabase.open(project)) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO artifacts(artifact_id, run_id, step_exec_id, kind, path, retained_at)
                    VALUES (?, ?, ?, 'screenshot', ?, ?)
                    ON CONFLICT(artifact_id) DO UPDATE SET
                        step_exec_id = COALESCE(excluded.step_exec_id, artifacts.step_exec_id),
                        path = excluded.path
                    """)) {
                statement.setString(1, id);
                statement.setString(2, runId);
                if (stepExecId == null || stepExecId.isBlank()) statement.setNull(3, java.sql.Types.VARCHAR);
                else statement.setString(3, stepExecId);
                statement.setString(4, relative);
                statement.setString(5, now);
                statement.executeUpdate();
            }
        }
        DoltLog.log(project, "track", runId, "artifact " + relative);
        return true;
    }

    public static List<Artifact> list(Path project, String runId) throws SQLException {
        if (project == null || runId == null || !DoltPaths.ready(project)) return List.of();
        try (DoltDatabase database = DoltDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT artifact_id, run_id, step_exec_id, kind, path, retained_at
                     FROM artifacts WHERE run_id = ? ORDER BY path
                     """)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Artifact> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new Artifact(
                            rows.getString(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getString(5), rows.getString(6)));
                }
                return found;
            }
        }
    }

    private static boolean image(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".gif");
    }
}
