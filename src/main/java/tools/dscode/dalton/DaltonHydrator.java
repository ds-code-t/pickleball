package tools.dscode.dalton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Copies source files into the Dalton database. Unchanged hashes are skipped. */
public final class DaltonHydrator {
    private DaltonHydrator() {
    }

    public record Options(List<String> paths, boolean force, boolean dryRun) {
        public Options {
            paths = paths == null ? List.of() : List.copyOf(paths);
        }

        public static Options full() {
            return new Options(List.of(), false, false);
        }
    }

    public record Result(
            int inserted,
            int updated,
            int skipped,
            int conflicts,
            boolean dryRun,
            List<String> conflictPaths,
            List<String> notices,
            int ignored
    ) {
        public Result {
            conflictPaths = conflictPaths == null ? List.of() : List.copyOf(conflictPaths);
            notices = notices == null ? List.of() : List.copyOf(notices);
        }

        public String line() {
            String prefix = dryRun ? "dry-run" : "hydrated";
            return prefix + " inserted=" + inserted + " updated=" + updated
                    + " skipped=" + skipped + " conflicts=" + conflicts
                    + " ignored=" + ignored;
        }
    }

    public static Result hydrate(Path project, Options options) throws IOException, SQLException {
        boolean bypassLimits = options.force() && !options.paths().isEmpty();
        DaltonSources.Scan scan = DaltonSources.scan(project, options.paths(), bypassLimits);
        DaltonDiscovery discovery = DaltonDiscovery.load(project);
        List<DaltonSources.Found> sources = scan.found();
        int ignored = scan.skipped().size();
        boolean present = Files.isRegularFile(DaltonPaths.database(project));
        if (options.dryRun() && !present) {
            return new Result(sources.size(), 0, 0, 0, true, List.of(), List.of(), ignored);
        }
        if (!DaltonDatabase.driverPresent()) {
            throw new SQLException(DaltonDatabase.MISSING_DRIVER);
        }
        String now = Instant.now().toString();
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            Connection connection = database.connection();
            Map<String, DaltonFiles.Row> existing = DaltonFiles.index(connection);
            int inserted = 0;
            int updated = 0;
            int skipped = 0;
            List<String> conflicts = new ArrayList<>();
            List<String> notices = new ArrayList<>();
            connection.setAutoCommit(false);
            try {
                for (DaltonSources.Found source : sources) {
                    byte[] raw = Files.readAllBytes(source.file());
                    String hash = DaltonHashes.sha256(raw);
                    long mtime = Files.getLastModifiedTime(source.file()).toMillis();
                    DaltonFiles.Row row = existing.get(source.path());
                    if (row == null) {
                        inserted++;
                        if (!options.dryRun()) {
                            writeSource(connection, project, discovery, source, raw, hash, mtime, now);
                        }
                        continue;
                    }
                    if (DaltonContent.untracked(row) && !options.force()) {
                        conflicts.add(source.path());
                        notices.add(DaltonContent.untrackedMessage(source.path()));
                        DaltonLog.log(project, "hydrate", source.path(), DaltonContent.untrackedMessage(source.path()));
                        continue;
                    }
                    boolean same = hash.equals(row.contentHash());
                    boolean fileMoved = row.lastSyncedHash() != null && !hash.equals(row.lastSyncedHash());
                    if (row.edited() && fileMoved && !options.force()) {
                        conflicts.add(source.path());
                        continue;
                    }
                    // A flagged edit whose file is still the last sync is the working copy.
                    // Hydrate must not replace it with the older file.
                    if (row.edited() && !fileMoved && !options.force()) {
                        skipped++;
                        continue;
                    }
                    if (same && !options.force()) {
                        skipped++;
                        if (!options.dryRun()) {
                            DaltonGherkin.storeIfMissing(connection, project, source.path(), source.sourceType(), raw);
                            if (DaltonEntities.isRecord(source.path(), source.sourceType())) {
                                DaltonEntities.storeIfMissing(connection, project, source.path(), raw);
                            }
                        }
                        continue;
                    }
                    updated++;
                    if (!options.dryRun()) {
                        writeSource(connection, project, discovery, source, raw, hash, mtime, now);
                    }
                }
                if (!options.dryRun()) {
                    DaltonGit.Snapshot git = DaltonGit.capture(project);
                    try (var statement = connection.prepareStatement("""
                            UPDATE provenance SET hydration_id = ?, last_hydrated_at = ?, schema_version = ?,
                                git_commit = ?, git_branch = ?, git_remote = ?, github_repo = ?, git_dirty = ?
                            WHERE id = 1
                            """)) {
                        statement.setString(1, UUID.randomUUID().toString());
                        statement.setString(2, now);
                        statement.setInt(3, DaltonDatabase.SCHEMA_VERSION);
                        statement.setString(4, git.commit());
                        statement.setString(5, git.branch());
                        statement.setString(6, git.remote());
                        statement.setString(7, git.githubRepo());
                        statement.setInt(8, git.available() && git.dirty() ? 1 : 0);
                        statement.executeUpdate();
                    }
                    connection.commit();
                } else {
                    connection.rollback();
                }
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
            DaltonLog.log(project, "hydrate", "files",
                    (options.dryRun() ? "dry-run " : "") + "inserted=" + inserted + " updated=" + updated
                            + " ignored=" + ignored);
            return new Result(inserted, updated, skipped, conflicts.size(), options.dryRun(), conflicts, notices, ignored);
        }
    }

    private static void writeSource(
            java.sql.Connection connection,
            Path project,
            DaltonDiscovery discovery,
            DaltonSources.Found source,
            byte[] raw,
            String hash,
            long mtime,
            String now
    ) throws java.sql.SQLException {
        byte[] body = raw == null ? new byte[0] : raw;
        String kind = discovery.binaryKind(source.file());
        boolean binaryRef = kind != null && discovery.hashOnly();
        byte[] stored = binaryRef ? new byte[0] : body;
        String sourceType = binaryRef ? "binary-ref" : source.sourceType();
        String gitBlob = DaltonHashes.gitBlob(body);
        if (!binaryRef && DaltonEntities.isRecord(source.path(), source.sourceType())) {
            DaltonEntities.parseRecord(project, body);
        }
        DaltonFiles.upsert(connection, new DaltonFiles.Row(
                source.path(), hash, stored, mtime, sourceType, now, hash, false,
                gitBlob, binaryRef, body.length));
        if (binaryRef) {
            DaltonLog.log(project, "binary", source.path(), "binary-ref " + kind + " bytes=" + body.length);
            return;
        }
        DaltonGherkin.store(connection, project, source.path(), source.sourceType(), body);
        if (DaltonEntities.isRecord(source.path(), source.sourceType())) {
            DaltonEntities.store(connection, project, source.path(), body);
        }
    }
}
