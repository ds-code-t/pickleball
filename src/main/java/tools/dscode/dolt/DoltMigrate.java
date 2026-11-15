package tools.dscode.dolt;

import tools.dscode.dolt.DoltLog;
import tools.dscode.dolt.install.DoltInstaller;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import tools.dscode.dolt.store.LegacySqlite;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Imports DB-edited rows from the previous SQLite working copy. This is the
 * only Dolt-layer reader of that SQLite file. Run history is not imported.
 */
public final class DoltMigrate {
    private static final Set<Path> MIGRATING = ConcurrentHashMap.newKeySet();

    private DoltMigrate() {
    }

    public record Result(int edits, int runs, List<String> notices, boolean blocked) {
        public Result {
            notices = notices == null ? List.of() : List.copyOf(notices);
        }
    }

    /** First store use. Does not fail the open when legacy names are still present. */
    public static void auto(Path project) throws SQLException {
        if (project == null || !Files.isRegularFile(DoltPaths.database(project))) return;
        if (Files.isRegularFile(marker(project))) return;
        Path key = project.toAbsolutePath().normalize();
        if (!MIGRATING.add(key)) return;
        try {
            migrate(project, false, false, false);
        } catch (IOException failure) {
            throw new SQLException(failure.getMessage() == null ? "legacy migrate failed" : failure.getMessage(), failure);
        } finally {
            MIGRATING.remove(key);
        }
    }

    public static Result migrate(Path project, boolean dryRun, boolean removeLegacy, boolean strictNames)
            throws IOException, SQLException {
        Path key = project.toAbsolutePath().normalize();
        boolean added = MIGRATING.add(key);
        try {
            return locked(key, dryRun, removeLegacy, strictNames);
        } finally {
            if (added) MIGRATING.remove(key);
        }
    }

    public static int cli(List<String> tokens, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        Path project = DoltInstaller.project(tokens);
        boolean dryRun = tokens.contains("--dry-run");
        boolean removeLegacy = tokens.contains("--remove-legacy");
        try {
            Result result = migrate(project, dryRun, removeLegacy, true);
            for (String notice : result.notices()) stderr.println(notice);
            if (result.blocked()) {
                stderr.println("error: legacy names are still in the project");
                stderr.println("fix: run the git mv lines above, then pickleball dolt migrate");
                return 1;
            }
            stdout.println("migrated edits=" + result.edits());
            stdout.println("history not imported: " + result.runs() + " runs");
            return 0;
        } catch (Exception failure) {
            stderr.println("error: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            stderr.println("fix: pickleball dolt migrate --dry-run");
            return 1;
        }
    }

    private static Result locked(Path project, boolean dryRun, boolean removeLegacy, boolean strictNames)
            throws IOException, SQLException {
        List<String> notices = legacyNotices(project);
        boolean blocked = strictNames && !notices.isEmpty();
        Path db = DoltPaths.database(project);
        if (!Files.isRegularFile(db)) {
            return new Result(0, 0, notices, blocked);
        }
        if (!dryRun && Files.isRegularFile(marker(project))) {
            if (removeLegacy) deleteTree(LegacyNames.legacyRoot(project));
            return new Result(0, 0, notices, blocked);
        }
        Legacy legacy = readLegacy(db);
        if (dryRun || blocked) {
            return new Result(legacy.rows().size(), legacy.runs(), notices, blocked);
        }
        DoltHydrator.hydrate(project, DoltHydrator.Options.full());
        try (DoltDatabase database = DoltDatabase.open(project)) {
            for (DoltFiles.Row row : legacy.rows()) {
                DoltFiles.upsert(database.connection(), row);
            }
        }
        Files.createDirectories(marker(project).getParent());
        Files.writeString(marker(project), "edits=" + legacy.rows().size() + "\nruns=" + legacy.runs() + "\n");
        if (removeLegacy) deleteTree(LegacyNames.legacyRoot(project));
        DoltLog.log(project, "migrate", "legacy", "edits=" + legacy.rows().size() + " runs=" + legacy.runs());
        return new Result(legacy.rows().size(), legacy.runs(), notices, false);
    }

    private static List<String> legacyNotices(Path project) {
        return LegacyNames.moveLines(project);
    }

    private static Path marker(Path project) {
        return project.resolve(".pickleball").resolve("dolt").resolve("legacy-migrated");
    }

    private record Legacy(List<DoltFiles.Row> rows, int runs) {
    }

    private static Legacy readLegacy(Path db) throws SQLException {
        try (Connection connection = LegacySqlite.openReadOnly(db);
             Statement statement = connection.createStatement()) {
            int runs = 0;
            if (tableExists(connection, "runs")) {
                try (ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM runs")) {
                    if (count.next()) runs = count.getInt(1);
                }
            }
            List<DoltFiles.Row> rows = new ArrayList<>();
            if (!tableExists(connection, "files")) return new Legacy(rows, runs);
            Set<String> columns = columns(connection, "files");
            String sql = "SELECT path, content_hash, raw_content, mtime, source_type, last_hydrated_at, "
                    + "last_synced_hash, db_edited"
                    + (columns.contains("git_blob") ? ", git_blob" : "")
                    + (columns.contains("binary_ref") ? ", binary_ref" : "")
                    + (columns.contains("byte_size") ? ", byte_size" : "")
                    + " FROM files WHERE db_edited = 1";
            try (ResultSet result = statement.executeQuery(sql)) {
                while (result.next()) {
                    byte[] raw = result.getBytes(3);
                    if (raw == null) raw = new byte[0];
                    String gitBlob = columns.contains("git_blob") ? result.getString("git_blob") : null;
                    boolean binary = columns.contains("binary_ref") && result.getInt("binary_ref") != 0;
                    long size = columns.contains("byte_size") ? result.getLong("byte_size") : raw.length;
                    if (result.wasNull() && columns.contains("byte_size")) size = raw.length;
                    rows.add(new DoltFiles.Row(
                            result.getString(1),
                            result.getString(2),
                            raw,
                            result.getLong(4),
                            result.getString(5),
                            result.getString(6),
                            result.getString(7),
                            true,
                            gitBlob,
                            binary,
                            size));
                }
            }
            return new Legacy(rows, runs);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'")) {
            return rows.next();
        }
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException {
        Set<String> names = new java.util.LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                String name = rows.getString("name");
                if (name != null) names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
