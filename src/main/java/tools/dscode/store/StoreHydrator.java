package tools.dscode.store;

import tools.dscode.engine.adapter.cucumber.CucumberIngest;
import tools.dscode.engine.adapter.playwright.PlaywrightTestIngest;
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
            int ignored,
            int translated,
            int failed
    ) {
        public Result(
                int inserted,
                int updated,
                int skipped,
                int conflicts,
                boolean dryRun,
                List<String> conflictPaths,
                List<String> notices,
                int ignored
        ) {
            this(inserted, updated, skipped, conflicts, dryRun, conflictPaths, notices, ignored, 0, 0);
        }

        public Result {
            conflictPaths = conflictPaths == null ? List.of() : List.copyOf(conflictPaths);
            notices = notices == null ? List.of() : List.copyOf(notices);
        }

        public String line() {
            String prefix = dryRun ? "dry-run" : "hydrated";
            return prefix + " inserted=" + inserted + " updated=" + updated
                    + " skipped=" + skipped + " conflicts=" + conflicts
                    + " ignored=" + ignored
                    + " translated=" + translated + " failed=" + failed;
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
            int reused = 0;
            int rehydrated = 0;
            List<String> conflicts = new ArrayList<>();
            List<String> notices = new ArrayList<>();
            int translated = 0;
            int failed = 0;
            String jar = StoreHashes.jarHash();
            connection.setAutoCommit(false);
            try {
                if (!options.dryRun() && options.paths().isEmpty() && jarDiffers(connection, jar)) {
                    wipeSourceRows(connection);
                    existing.clear();
                }
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
                        rehydrated++;
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
                        reused++;
                        if (!options.dryRun()) {
                            StoreGherkin.storeIfMissing(connection, project, source.path(), source.sourceType(), raw);
                            StoreGherkin.applyFlags(connection, source.path(), source.sourceType(), raw);
                            if (StoreEntities.isRecord(source.path(), source.sourceType())) {
                                StoreEntities.storeIfMissing(connection, project, source.path(), raw);
                            }
                        }
                        continue;
                    }
                    updated++;
                    rehydrated++;
                    if (!options.dryRun()) {
                        StoreRefs.Decision decision = StoreRefs.decide(
                                project, discovery, source.path(), source.sourceType(), raw, hash, mtime, now, head, bypassLimits);
                        if (decision.skip()) {
                            ignored++;
                            updated--;
                            rehydrated--;
                            StoreLog.log(project, "discover", source.path(), decision.skipReason());
                            continue;
                        }
                        persist(connection, project, decision.row());
                    }
                }
                if (!options.dryRun()) {
                    if (StoreScope.current().primary()) {
                        StoreRunVariables.sync(connection, project);
                    }
                    UniversalEntity.putMeta(connection, "jar_hash", jar);
                    UniversalEntity.putMeta(connection, "hydrate_reused", Integer.toString(reused));
                    UniversalEntity.putMeta(connection, "hydrate_rehydrated", Integer.toString(rehydrated));
                    stampJar(connection, jar);
                    var prior = CucumberIngest.snapshot(connection);
                    StoreStepCatalog.refresh(connection, project, now);
                    CucumberIngest.Counts ingest = CucumberIngest.apply(connection, project, prior);
                    PlaywrightTestIngest.apply(connection, project);
                    notices.addAll(ingest.notices());
                    translated = ingest.translated();
                    failed = ingest.failed();
                    connection.commit();
                } else {
                    connection.rollback();
                }
            } catch (SQLException | IOException | RuntimeException failure) {
                connection.rollback();
                if (failure instanceof SQLException sql) throw sql;
                if (failure instanceof IOException io) throw io;
                throw (RuntimeException) failure;
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
            if (!options.dryRun()) {
                HeadMaterializer.quiet(connection, project);
                UniversalEntity.refreshPin(connection, project);
            }
            StoreLog.log(project, "hydrate", "files",
                    (options.dryRun() ? "dry-run " : "") + "inserted=" + inserted + " updated=" + updated
                            + " ignored=" + ignored);
            return new Result(
                    inserted, updated, skipped, conflicts.size(), options.dryRun(), conflicts, notices, ignored,
                    translated, failed);
        }
    }

    private static boolean jarDiffers(java.sql.Connection connection, String jar) throws java.sql.SQLException {
        String stored = UniversalEntity.meta(connection, "jar_hash");
        return stored == null || !stored.equals(jar);
    }

    /** Source projection only. Run history in runstate.db is not opened. */
    private static void wipeSourceRows(java.sql.Connection connection) throws java.sql.SQLException {
        for (String table : List.of(
                "files", "feature_docs", "scenarios", "example_blocks", "steps", "entities",
                "step_definitions", "fixtures", "head_rows", "deltas", "heads", "folders", "scenario_tags", "run_variables")) {
            try (var statement = connection.prepareStatement("DELETE FROM " + table)) {
                statement.executeUpdate();
            }
        }
        UniversalEntity.putMeta(connection, UniversalEntity.META_CURSOR, "0");
    }

    private static void stampJar(java.sql.Connection connection, String jar) throws java.sql.SQLException {
        try (var update = connection.prepareStatement("UPDATE heads SET jar_hash = ? WHERE head_id = ?")) {
            update.setString(1, jar);
            update.setString(2, UniversalEntity.HEAD_ID);
            if (update.executeUpdate() > 0) return;
        }
        try (var insert = connection.prepareStatement(
                "INSERT INTO heads(head_id, built_at, stale, through_seq, jar_hash) VALUES (?, ?, 0, 0, ?)")) {
            insert.setString(1, UniversalEntity.HEAD_ID);
            insert.setString(2, Instant.now().toString());
            insert.setString(3, jar);
            insert.executeUpdate();
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
