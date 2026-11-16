package tools.dscode.store;

import tools.dscode.store.sql.SqlDialect;

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

/** Copies source files into the Store database. Unchanged hashes are skipped. */
public final class StoreHydrator {
    private StoreHydrator() {
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
        StoreSources.Scan scan = StoreSources.scan(project, options.paths(), bypassLimits);
        StoreDiscovery discovery = StoreDiscovery.load(project);
        StoreGit.Head head = StoreGit.head(project);
        List<StoreSources.Found> sources = scan.found();
        int ignored = scan.skipped().size();
        boolean present = StorePaths.ready(project);
        if (options.dryRun() && !present) {
            return new Result(sources.size(), 0, 0, 0, true, List.of(), List.of(), ignored);
        }
        if (!StoreDatabase.driverPresent()) {
            throw new SQLException(StoreDatabase.MISSING_DRIVER);
        }
        String now = Instant.now().toString();
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            Map<String, StoreFiles.Row> existing = StoreFiles.index(connection);
            int inserted = 0;
            int updated = 0;
            int skipped = 0;
            List<String> conflicts = new ArrayList<>();
            List<String> notices = new ArrayList<>();
            connection.setAutoCommit(false);
            try {
                for (StoreSources.Found source : sources) {
                    byte[] raw = Files.readAllBytes(source.file());
                    String hash = StoreHashes.sha256(raw);
                    long mtime = Files.getLastModifiedTime(source.file()).toMillis();
                    StoreFiles.Row row = existing.get(source.path());
                    if (row == null) {
                        StoreRefs.Decision decision = StoreRefs.decide(
                                project, discovery, source.path(), source.sourceType(), raw, hash, mtime, now, head, bypassLimits);
                        if (decision.skip()) {
                            ignored++;
                            StoreLog.log(project, "discover", source.path(), decision.skipReason());
                            continue;
                        }
                        inserted++;
                        if (!options.dryRun()) persist(connection, project, decision.row());
                        continue;
                    }
                    if (StoreContent.untracked(row) && !options.force()) {
                        conflicts.add(source.path());
                        notices.add(StoreContent.untrackedMessage(source.path()));
                        StoreLog.log(project, "hydrate", source.path(), StoreContent.untrackedMessage(source.path()));
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
                            StoreGherkin.storeIfMissing(connection, project, source.path(), source.sourceType(), raw);
                            if (StoreEntities.isRecord(source.path(), source.sourceType())) {
                                StoreEntities.storeIfMissing(connection, project, source.path(), raw);
                            }
                        }
                        continue;
                    }
                    updated++;
                    if (!options.dryRun()) {
                        StoreRefs.Decision decision = StoreRefs.decide(
                                project, discovery, source.path(), source.sourceType(), raw, hash, mtime, now, head, bypassLimits);
                        if (decision.skip()) {
                            ignored++;
                            updated--;
                            StoreLog.log(project, "discover", source.path(), decision.skipReason());
                            continue;
                        }
                        persist(connection, project, decision.row());
                    }
                }
                if (!options.dryRun()) {
                    StoreStepCatalog.refresh(connection, project, now);
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
                StoreGit.Snapshot git = StoreGit.capture(project);
                try (var statement = connection.prepareStatement("""
                        UPDATE provenance SET hydration_id = ?, last_hydrated_at = ?, schema_version = ?,
                            git_commit = ?, git_branch = ?, git_remote = ?, github_repo = ?, git_dirty = ?
                        WHERE id = 1
                        """)) {
                    statement.setString(1, UUID.randomUUID().toString());
                    statement.setString(2, now);
                    statement.setInt(3, StoreDatabase.SCHEMA_VERSION);
                    statement.setString(4, git.commit());
                    statement.setString(5, git.branch());
                    statement.setString(6, git.remote());
                    statement.setString(7, git.githubRepo());
                    statement.setInt(8, git.available() && git.dirty() ? 1 : 0);
                    statement.executeUpdate();
                }
                String sha = git.available() && git.commit() != null && !git.commit().isBlank()
                        ? git.commit() : "unknown";
                SqlDialect.commitHydrate(connection, sha, git.available() && git.dirty());
            }
            StoreLog.log(project, "hydrate", "files",
                    (options.dryRun() ? "dry-run " : "") + "inserted=" + inserted + " updated=" + updated
                            + " ignored=" + ignored);
            return new Result(inserted, updated, skipped, conflicts.size(), options.dryRun(), conflicts, notices, ignored);
        }
    }

    private static void persist(java.sql.Connection connection, Path project, StoreFiles.Row row) throws java.sql.SQLException {
        StoreFiles.upsert(connection, row);
        if (row.binaryRef()) {
            StoreLog.log(project, "refs", row.path(), "reference bytes=" + row.byteSize() + " commit=" + row.gitCommit());
            return;
        }
        if ("binary".equals(row.sourceType())) return;
        byte[] body = row.raw() == null ? new byte[0] : row.raw();
        StoreGherkin.store(connection, project, row.path(), row.sourceType(), body);
        if (StoreEntities.isRecord(row.path(), row.sourceType())) {
            StoreEntities.store(connection, project, row.path(), body);
        }
    }
}
