package tools.dscode.dalton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Writes stored file bytes back to disk. An unedited row is not rewritten, so
 * the original bytes stay. A row edited only in the database is copied out
 * exactly. Both sides changed is a conflict unless {@code --force} is set.
 */
public final class DaltonExtractor {
    private DaltonExtractor() {
    }

    public record Options(List<String> paths, boolean force) {
        public Options {
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }

    public record Result(int written, int skipped, int conflicts, List<String> conflictPaths) {
        public Result {
            conflictPaths = conflictPaths == null ? List.of() : List.copyOf(conflictPaths);
        }

        public String line() {
            return "extracted written=" + written + " skipped=" + skipped + " conflicts=" + conflicts;
        }
    }

    public static Result extract(Path project, Options options) throws IOException, SQLException {
        if (!Files.isRegularFile(DaltonPaths.database(project))) {
            return new Result(0, 0, 0, List.of());
        }
        if (!DaltonDatabase.driverPresent()) {
            throw new SQLException(DaltonDatabase.MISSING_DRIVER);
        }
        String now = Instant.now().toString();
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            Connection connection = database.connection();
            List<DaltonFiles.Row> rows = new ArrayList<>(DaltonFiles.index(connection).values());
            rows.sort(Comparator.comparing(DaltonFiles.Row::path));
            int written = 0;
            int skipped = 0;
            List<String> conflicts = new ArrayList<>();
            connection.setAutoCommit(false);
            try {
                for (DaltonFiles.Row row : rows) {
                    if (!selected(row.path(), options.paths())) continue;
                    switch (apply(project, connection, row, options.force(), now)) {
                        case WRITTEN -> written++;
                        case SKIPPED -> skipped++;
                        case CONFLICT -> conflicts.add(row.path());
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException | IOException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            DaltonLog.log(project, "extract", "files",
                    "written=" + written + " skipped=" + skipped + " conflicts=" + conflicts.size());
            for (String conflict : conflicts) {
                DaltonLog.log(project, "extract", conflict, "conflict");
            }
            return new Result(written, skipped, conflicts.size(), conflicts);
        }
    }

    private enum Outcome {
        WRITTEN, SKIPPED, CONFLICT
    }

    private static Outcome apply(
            Path project,
            Connection connection,
            DaltonFiles.Row row,
            boolean force,
            String now
    ) throws IOException, SQLException {
        byte[] desired = row.raw() == null ? new byte[0] : row.raw();
        Path disk = project.resolve(row.path());
        byte[] existing = Files.isRegularFile(disk) ? Files.readAllBytes(disk) : null;
        String diskHash = existing == null ? null : DaltonHashes.sha256(existing);
        String synced = row.lastSyncedHash() == null ? "" : row.lastSyncedHash();
        boolean fileMoved = existing != null && !synced.isBlank() && !diskHash.equals(synced);
        if (fileMoved && row.edited() && !force) {
            return Outcome.CONFLICT;
        }
        if (!row.edited() && !force) {
            return Outcome.SKIPPED;
        }
        if (disk.getParent() != null) {
            Files.createDirectories(disk.getParent());
        }
        Files.write(disk, desired);
        String hash = DaltonHashes.sha256(desired);
        long mtime = Files.getLastModifiedTime(disk).toMillis();
        DaltonFiles.upsert(connection, new DaltonFiles.Row(
                row.path(), hash, desired, mtime, row.sourceType(), now, hash, false));
        return Outcome.WRITTEN;
    }

    private static boolean selected(String path, List<String> only) {
        if (only.isEmpty()) return true;
        for (String raw : only) {
            String wanted = DaltonPaths.normalizeRelative(raw);
            if (path.equals(wanted) || path.startsWith(wanted.endsWith("/") ? wanted : wanted + "/")) {
                return true;
            }
        }
        return false;
    }
}
