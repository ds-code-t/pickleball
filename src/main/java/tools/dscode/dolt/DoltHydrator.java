package tools.dscode.dolt;

import tools.dscode.dolt.store.DoltDialect;

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

/** Copies source files into the Dolt database. Unchanged hashes are skipped. */
public final class DoltHydrator {
    private DoltHydrator() {
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
        DoltSources.Scan scan = DoltSources.scan(project, options.paths(), bypassLimits);
        DoltDiscovery discovery = DoltDiscovery.load(project);
        List<DoltSources.Found> sources = scan.found();
        int ignored = scan.skipped().size();
        boolean present = DoltPaths.ready(project);
        if (options.dryRun() && !present) {
            return new Result(sources.size(), 0, 0, 0, true, List.of(), List.of(), ignored);
        }
        if (!DoltDatabase.driverPresent()) {
            throw new SQLException(DoltDatabase.MISSING_DRIVER);
        }
        String now = Instant.now().toString();
        try (DoltDatabase database = DoltDatabase.open(project)) {
            Connection connection = database.connection();
            Map<String, DoltFiles.Row> existing = DoltFiles.index(connection);
            int inserted = 0;
            int updated = 0;
            int skipped = 0;
            List<String> conflicts = new ArrayList<>();
            List<String> notices = new ArrayList<>();
            connection.setAutoCommit(false);
            try {
                for (DoltSources.Found source : sources) {
                    byte[] raw = Files.readAllBytes(source.file());
                    String hash = DoltHashes.sha256(raw);
                    long mtime = Files.getLastModifiedTime(source.file()).toMillis();
                    DoltFiles.Row row = existing.get(source.path());
                    if (row == null) {
                        inserted++;
                        if (!options.dryRun()) {
                            writeSource(connection, project, discovery, source, raw, hash, mtime, now);
                        }
                        continue;
                    }
                    if (DoltContent.untracked(row) && !options.force()) {
                        conflicts.add(source.path());
                        notices.add(DoltContent.untrackedMessage(source.path()));
                        DoltLog.log(project, "hydrate", source.path(), DoltContent.untrackedMessage(source.path()));
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
                            DoltGherkin.storeIfMissing(connection, project, source.path(), source.sourceType(), raw);
                            if (DoltEntities.isRecord(source.path(), source.sourceType())) {
                                DoltEntities.storeIfMissing(connection, project, source.path(), raw);
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
                    DoltStepCatalog.refresh(connection, project, now);
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
            if (!options.dryRun() && inserted + updated > 0) {
                DoltGit.Snapshot git = DoltGit.capture(project);
                try (var statement = connection.prepareStatement("""
                        UPDATE provenance SET hydration_id = ?, last_hydrated_at = ?, schema_version = ?,
                            git_commit = ?, git_branch = ?, git_remote = ?, github_repo = ?, git_dirty = ?
                        WHERE id = 1
                        """)) {
                    statement.setString(1, UUID.randomUUID().toString());
                    statement.setString(2, now);
                    statement.setInt(3, DoltDatabase.SCHEMA_VERSION);
                    statement.setString(4, git.commit());
                    statement.setString(5, git.branch());
                    statement.setString(6, git.remote());
                    statement.setString(7, git.githubRepo());
                    statement.setInt(8, git.available() && git.dirty() ? 1 : 0);
                    statement.executeUpdate();
                }
                String sha = git.available() && git.commit() != null && !git.commit().isBlank()
                        ? git.commit() : "unknown";
                DoltDialect.commitHydrate(connection, sha, git.available() && git.dirty());
            }
            DoltLog.log(project, "hydrate", "files",
                    (options.dryRun() ? "dry-run " : "") + "inserted=" + inserted + " updated=" + updated
                            + " ignored=" + ignored);
            return new Result(inserted, updated, skipped, conflicts.size(), options.dryRun(), conflicts, notices, ignored);
        }
    }

    private static void writeSource(
            java.sql.Connection connection,
            Path project,
            DoltDiscovery discovery,
            DoltSources.Found source,
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
        String gitBlob = DoltHashes.gitBlob(body);
        if (!binaryRef && DoltEntities.isRecord(source.path(), source.sourceType())) {
            DoltEntities.parseRecord(project, body);
        }
        DoltFiles.upsert(connection, new DoltFiles.Row(
                source.path(), hash, stored, mtime, sourceType, now, hash, false,
                gitBlob, binaryRef, body.length));
        if (binaryRef) {
            DoltLog.log(project, "binary", source.path(), "binary-ref " + kind + " bytes=" + body.length);
            return;
        }
        DoltGherkin.store(connection, project, source.path(), source.sourceType(), body);
        if (DoltEntities.isRecord(source.path(), source.sourceType())) {
            DoltEntities.store(connection, project, source.path(), body);
        }
    }
}
