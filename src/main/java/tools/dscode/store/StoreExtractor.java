package tools.dscode.store;

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
public final class StoreExtractor {
    private StoreExtractor() {
    }

    public record Options(List<String> paths, boolean force) {
        public Options {
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }

    public record Result(int written, int skipped, int conflicts, List<String> conflictPaths, List<String> notices) {
        public Result {
            conflictPaths = conflictPaths == null ? List.of() : List.copyOf(conflictPaths);
            notices = notices == null ? List.of() : List.copyOf(notices);
        }

        public Result(int written, int skipped, int conflicts, List<String> conflictPaths) {
            this(written, skipped, conflicts, conflictPaths, List.of());
        }

        public String line() {
            String text = "extracted written=" + written + " skipped=" + skipped + " conflicts=" + conflicts;
            if (!notices.isEmpty()) text = text + " " + String.join(" ", notices);
            return text;
        }
    }

    public static Result extract(Path project, Options options) throws IOException, SQLException {
        if (!StorePaths.ready(project)) {
            return new Result(0, 0, 0, List.of(), List.of());
        }
        if (!StoreDatabase.driverPresent()) {
            throw new SQLException(StoreDatabase.MISSING_DRIVER);
        }
        String now = Instant.now().toString();
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            List<StoreFiles.Row> rows = new ArrayList<>(StoreFiles.index(connection).values());
            rows.sort(Comparator.comparing(StoreFiles.Row::path));
            int written = 0;
            int skipped = 0;
            List<String> conflicts = new ArrayList<>();
            List<String> notices = new ArrayList<>();
            connection.setAutoCommit(false);
            try {
                for (StoreFiles.Row row : rows) {
                    if (!selected(row.path(), options.paths())) continue;
                    switch (apply(project, connection, row, options.force(), now, notices)) {
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
            StoreLog.log(project, "extract", "files",
                    "written=" + written + " skipped=" + skipped + " conflicts=" + conflicts.size());
            for (String conflict : conflicts) {
                StoreLog.log(project, "extract", conflict, "conflict");
            }
            for (String notice : notices) {
                StoreLog.log(project, "binary", notice, notice);
            }
            return new Result(written, skipped, conflicts.size(), conflicts, notices);
        }
    }

    private enum Outcome {
        WRITTEN, SKIPPED, CONFLICT
    }

    private static Outcome apply(
            Path project,
            Connection connection,
            StoreFiles.Row row,
            boolean force,
            String now,
            List<String> notices
    ) throws IOException, SQLException {
        if (row.binaryRef()) {
            Path disk = project.resolve(row.path());
            byte[] existing = Files.isRegularFile(disk) ? Files.readAllBytes(disk) : new byte[0];
            String diskHash = StoreHashes.sha256(existing);
            if (!diskHash.equals(row.contentHash())) {
                notices.add("binary_ref_changed " + row.path());
            }
            return Outcome.SKIPPED;
        }
        byte[] desired = row.raw() == null ? new byte[0] : row.raw();
        Path disk = project.resolve(row.path());
        byte[] existing = Files.isRegularFile(disk) ? Files.readAllBytes(disk) : null;
        String diskHash = existing == null ? null : StoreHashes.sha256(existing);
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
        String hash = StoreHashes.sha256(desired);
        long mtime = Files.getLastModifiedTime(disk).toMillis();
        StoreFiles.upsert(connection, new StoreFiles.Row(
                row.path(), hash, desired, mtime, row.sourceType(), now, hash, false));
        return Outcome.WRITTEN;
    }

    private static boolean selected(String path, List<String> only) {
        if (only.isEmpty()) return true;
        for (String raw : only) {
            String wanted = StorePaths.normalizeRelative(raw);
            if (path.equals(wanted) || path.startsWith(wanted.endsWith("/") ? wanted : wanted + "/")) {
                return true;
            }
        }
        return false;
    }
}
