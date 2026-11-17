package tools.dscode.store;

import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlSession;
import tools.dscode.store.sql.SqlStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Three-way hash merge of one sandbox into {@code main}. Reports show hashes
 * and sizes, never file bytes or secret values.
 */
public final class StoreMerge {
    private StoreMerge() {
    }

    public record Outcome(
            boolean ok,
            String sandbox,
            String policy,
            boolean dryRun,
            String createdFromCommit,
            int applied,
            int converged,
            int wouldApply,
            int conflicts,
            Path report,
            String json,
            String text
    ) {
    }

    public static Outcome run(Path project, String name, String policy, boolean dryRun) throws Exception {
        Binding.sandbox(name);
        if (!Files.isRegularFile(StorePaths.sandboxDatabase(project, name))) {
            throw new IllegalArgumentException("unknown sandbox " + name + "\nfix: store sandbox create " + name);
        }
        String chosen = policy == null || policy.isBlank() ? "fail" : policy.trim();
        if (!chosen.equals("fail") && !chosen.equals("branch-wins") && !chosen.equals("main-wins")) {
            throw new IllegalArgumentException("unknown merge policy: " + chosen
                    + "\nfix: store sandbox merge " + name + " --policy=fail|branch-wins|main-wins");
        }
        Path sandboxFile = StorePaths.sandboxDatabase(project, name);
        Plan plan;
        try (SqlStore store = SqlStore.open(project, Binding.main())) {
            SqlSession session = store.session();
            attach(session.connection(), sandboxFile);
            try {
                if (dryRun) {
                    plan = read(session.connection(), name, chosen, true);
                } else {
                    Plan[] holder = new Plan[1];
                    session.transaction("files", () -> {
                        Plan loaded = read(session.connection(), name, chosen, false);
                        holder[0] = loaded;
                        if (!loaded.blocked && loaded.writes > 0) {
                            actor(session.connection(), "main merge");
                            try {
                                apply(session.connection(), project, loaded);
                            } finally {
                                actor(session.connection(), "");
                            }
                            loaded.applied = loaded.writes;
                        }
                    });
                    plan = holder[0];
                }
            } finally {
                detach(session.connection());
            }
        }
        if (plan == null) throw new IllegalStateException("merge did not read " + name);
        Path report = null;
        if (!dryRun) {
            report = writeReport(project, name, plan);
            if (!plan.blocked) rebase(project, name);
        }
        String json = json(plan, report);
        String text = text(plan, report);
        return new Outcome(!plan.blocked, name, chosen, dryRun, plan.createdFrom, plan.applied, plan.converged,
                plan.wouldApply, plan.conflictCount, report, json, text);
    }

    /** Re-baseline on the sandbox file after main has committed. */
    private static void rebase(Path project, String name) throws Exception {
        try (SqlStore store = SqlStore.open(project, Binding.sandbox(name))) {
            store.session().transaction("files", () -> SqlStore.writeBase(store.session().connection()));
        }
    }

    private static void attach(Connection connection, Path file) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            boolean attached = false;
            try (ResultSet rows = statement.executeQuery("PRAGMA database_list")) {
                while (rows.next()) {
                    if ("sbx".equalsIgnoreCase(rows.getString("name"))) attached = true;
                }
            }
            if (attached) statement.execute("DETACH DATABASE sbx");
            String path = file.toAbsolutePath().toString().replace("'", "''");
            statement.execute("ATTACH DATABASE 'file:" + path + "?mode=ro' AS sbx");
        }
    }

    private static void detach(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            boolean attached = false;
            try (ResultSet rows = statement.executeQuery("PRAGMA database_list")) {
                while (rows.next()) {
                    if ("sbx".equalsIgnoreCase(rows.getString("name"))) attached = true;
                }
            }
            if (attached) statement.execute("DETACH DATABASE sbx");
        } catch (SQLException ignored) {
            // Closing the pooled connection detaches anything still attached.
        }
    }

    private static void actor(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE _pkb_actor SET name = ? WHERE id = 1")) {
            statement.setString(1, name == null ? "" : name);
            statement.executeUpdate();
        }
    }

    private static Plan read(Connection connection, String name, String policy, boolean dryRun) throws SQLException {
        Plan plan = new Plan(name, policy, dryRun);
        plan.createdFrom = scalar(connection, "SELECT value FROM sbx._pkb_branch WHERE `key` = 'created_from_commit'");
        if (plan.createdFrom == null) plan.createdFrom = "";
        Map<String, String> fileBase = base(connection, "files");
        Map<String, String> entityBase = base(connection, "entities");
        Map<String, StoreFiles.Row> mainFiles = files(connection, "files");
        Map<String, StoreFiles.Row> branchFiles = files(connection, "sbx.files");
        Map<String, Entity> mainEntities = entities(connection, "entities");
        Map<String, Entity> branchEntities = entities(connection, "sbx.entities");
        plan.items.addAll(fileItems(fileBase, mainFiles, branchFiles));
        plan.items.addAll(entityItems(entityBase, mainEntities, branchEntities));
        for (Item item : plan.items) {
            if (item.kind == Kind.APPLY) plan.wouldApply++;
            else if (item.kind == Kind.CONVERGED) plan.converged++;
            else if (item.kind == Kind.CONFLICT) plan.conflictCount++;
        }
        plan.blocked = "fail".equals(policy) && plan.conflictCount > 0;
        if (!plan.blocked && !dryRun) {
            for (Item item : plan.items) {
                if (item.kind == Kind.APPLY) plan.writes++;
                else if (item.kind == Kind.CONFLICT && "branch-wins".equals(policy)) plan.writes++;
            }
        }
        return plan;
    }

    private static List<Item> fileItems(
            Map<String, String> base,
            Map<String, StoreFiles.Row> main,
            Map<String, StoreFiles.Row> branch
    ) {
        List<Item> items = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(base.keySet());
        keys.addAll(main.keySet());
        keys.addAll(branch.keySet());
        for (String key : keys) {
            StoreFiles.Row mainRow = main.get(key);
            StoreFiles.Row branchRow = branch.get(key);
            String baseHash = base.containsKey(key) ? base.get(key) : null;
            String mainHash = mainRow == null ? null : mainRow.contentHash();
            String branchHash = branchRow == null ? null : branchRow.contentHash();
            Kind kind = decide(baseHash, branchHash, mainHash);
            long size = branchRow != null ? branchRow.byteSize() : (mainRow == null ? 0L : mainRow.byteSize());
            items.add(new Item("files", key, baseHash, mainHash, branchHash, size, kind, mainRow, branchRow, null, null));
        }
        return items;
    }

    private static List<Item> entityItems(
            Map<String, String> base,
            Map<String, Entity> main,
            Map<String, Entity> branch
    ) {
        List<Item> items = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(base.keySet());
        keys.addAll(main.keySet());
        keys.addAll(branch.keySet());
        for (String key : keys) {
            Entity mainRow = main.get(key);
            Entity branchRow = branch.get(key);
            String baseHash = base.containsKey(key) ? base.get(key) : null;
            String mainHash = mainRow == null ? null : mainRow.identity();
            String branchHash = branchRow == null ? null : branchRow.identity();
            Kind kind = decide(baseHash, branchHash, mainHash);
            long size = branchRow != null ? branchRow.payload.length() : (mainRow == null ? 0L : mainRow.payload.length());
            items.add(new Item("entities", key, baseHash, mainHash, branchHash, size, kind, null, null, mainRow, branchRow));
        }
        return items;
    }

    static Kind decide(String base, String branch, String main) {
        if (same(branch, base)) return Kind.SKIP;
        if (same(main, base)) return Kind.APPLY;
        if (same(branch, main)) return Kind.CONVERGED;
        return Kind.CONFLICT;
    }

    private static boolean same(String left, String right) {
        if (left == null && right == null) return true;
        if (left == null || right == null) return false;
        return left.equals(right);
    }

    private static void apply(Connection connection, Path project, Plan plan) throws SQLException {
        Map<String, byte[]> sources = new LinkedHashMap<>();
        for (Item item : plan.items) {
            if (!write(plan, item)) continue;
            if ("files".equals(item.table)) applyFile(connection, project, item, sources);
            else applyEntity(connection, item);
        }
        if (!sources.isEmpty()) {
            StoreStepCatalog.refreshSources(connection, project, sources, Instant.now().toString());
        }
    }

    private static boolean write(Plan plan, Item item) {
        if (item.kind == Kind.APPLY) return true;
        return item.kind == Kind.CONFLICT && "branch-wins".equals(plan.policy);
    }

    private static void applyFile(Connection connection, Path project, Item item, Map<String, byte[]> sources)
            throws SQLException {
        if (item.branchFile == null) {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM files WHERE path = ?")) {
                statement.setString(1, item.key);
                statement.executeUpdate();
            }
            if (projected(item.key, item.mainFile == null ? "" : item.mainFile.sourceType())) {
                StoreGherkin.clear(connection, item.key);
            }
            if (sourceLanguage(item.key) != null) sources.put(item.key, null);
            return;
        }
        StoreFiles.Row branch = item.branchFile;
        String synced = item.mainFile == null || item.mainFile.lastSyncedHash() == null ? "" : item.mainFile.lastSyncedHash();
        boolean owned = !branch.binaryRef();
        byte[] raw = branch.raw() == null ? new byte[0] : branch.raw();
        StoreFiles.Row row = new StoreFiles.Row(
                branch.path(),
                branch.contentHash(),
                owned ? raw : new byte[0],
                branch.mtime(),
                branch.sourceType(),
                branch.lastHydratedAt() == null ? Instant.now().toString() : branch.lastHydratedAt(),
                synced,
                true,
                branch.gitBlob(),
                !owned && branch.binaryRef(),
                branch.byteSize() > 0 ? branch.byteSize() : raw.length,
                owned ? "db" : branch.location(),
                owned ? "" : branch.gitCommit()
        );
        StoreFiles.upsert(connection, row);
        if (projected(row.path(), row.sourceType())) {
            StoreGherkin.store(connection, project, row.path(), row.sourceType(), raw);
        }
        if (sourceLanguage(row.path()) != null) sources.put(row.path(), raw);
    }

    private static void applyEntity(Connection connection, Item item) throws SQLException {
        int hash = item.key.indexOf('#');
        String type = hash < 0 ? item.key : item.key.substring(0, hash);
        String id = hash < 0 ? "" : item.key.substring(hash + 1);
        if (item.branchEntity == null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM entities WHERE entity_type = ? AND entity_id = ?")) {
                statement.setString(1, type);
                statement.setString(2, id);
                statement.executeUpdate();
            }
            return;
        }
        Entity entity = item.branchEntity;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO entities(entity_type, entity_id, version, payload_json, secret_refs, source_file, content_hash, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(entity_type, entity_id) DO UPDATE SET
                    version = excluded.version,
                    payload_json = excluded.payload_json,
                    secret_refs = excluded.secret_refs,
                    source_file = excluded.source_file,
                    content_hash = excluded.content_hash,
                    updated_at = excluded.updated_at
                """)) {
            statement.setString(1, entity.type);
            statement.setString(2, entity.id);
            statement.setInt(3, entity.version);
            statement.setString(4, entity.payload);
            statement.setString(5, entity.secretRefs);
            statement.setString(6, entity.sourceFile);
            statement.setString(7, entity.contentHash);
            statement.setString(8, entity.updatedAt);
            statement.executeUpdate();
        }
    }

    private static boolean projected(String path, String sourceType) {
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        String type = sourceType == null ? "" : sourceType;
        return lower.endsWith(".feature") || "feature".equals(type) || "call".equals(type) || "component".equals(type);
    }

    private static String sourceLanguage(String path) {
        if (path == null) return null;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".java") || lower.endsWith(".js") || lower.endsWith(".ts")) return lower;
        return null;
    }

    private static Path writeReport(Path project, String name, Plan plan) throws java.io.IOException {
        String stamp = Instant.now().toString().replace(":", "-");
        Path file = StorePaths.sandboxHome(project, name).resolve("merge-" + stamp + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json(plan, file));
        return file;
    }

    private static String json(Plan plan, Path report) {
        StringBuilder body = new StringBuilder();
        body.append("{\"ok\":").append(!plan.blocked)
                .append(",\"command\":\"sandbox merge\",\"sandbox\":\"").append(escape(plan.sandbox))
                .append("\",\"policy\":\"").append(escape(plan.policy))
                .append("\",\"createdFromCommit\":\"").append(escape(plan.createdFrom))
                .append("\",\"applied\":").append(plan.applied)
                .append(",\"converged\":").append(plan.converged)
                .append(",\"wouldApply\":").append(plan.wouldApply)
                .append(",\"dryRun\":").append(plan.dryRun)
                .append(",\"conflicts\":[");
        boolean first = true;
        for (Item item : plan.items) {
            if (item.kind != Kind.CONFLICT) continue;
            if (!first) body.append(',');
            first = false;
            body.append("{\"table\":\"").append(escape(item.table))
                    .append("\",\"key\":\"").append(escape(item.key))
                    .append("\",\"base\":\"").append(escape(show(item.base)))
                    .append("\",\"main\":\"").append(escape(show(item.mainHash)))
                    .append("\",\"branch\":\"").append(escape(show(item.branchHash)))
                    .append("\",\"resolution\":\"").append(resolution(plan, item))
                    .append("\"}");
        }
        body.append("]}");
        return body.toString();
    }

    private static String text(Plan plan, Path report) {
        StringBuilder body = new StringBuilder();
        body.append("sandbox merge ").append(plan.sandbox).append('\n');
        body.append("policy ").append(plan.policy).append('\n');
        body.append("ok ").append(!plan.blocked).append('\n');
        body.append("createdFromCommit ").append(plan.createdFrom).append('\n');
        body.append("applied ").append(plan.applied).append('\n');
        body.append("converged ").append(plan.converged).append('\n');
        body.append("wouldApply ").append(plan.wouldApply).append('\n');
        body.append("conflicts ").append(plan.conflictCount).append('\n');
        if (report != null) body.append("report ").append(report).append('\n');
        body.append("table\n");
        for (Item item : plan.items) {
            if (item.kind == Kind.SKIP) continue;
            body.append(item.table).append('\t').append(item.key).append('\t')
                    .append(show(item.base)).append('\t')
                    .append(show(item.mainHash)).append('\t')
                    .append(show(item.branchHash)).append('\t')
                    .append(item.size).append('\t')
                    .append(resolution(plan, item)).append('\n');
        }
        if (plan.blocked) {
            body.append("fix: store sandbox merge ").append(plan.sandbox).append(" --policy=branch-wins\n");
            body.append("fix: store sandbox merge ").append(plan.sandbox).append(" --policy=main-wins\n");
            body.append("fix: store sandbox extract ").append(plan.sandbox).append(" --to=<dir> and commit with Git\n");
        }
        return body.toString();
    }

    private static String resolution(Plan plan, Item item) {
        if (item.kind == Kind.CONFLICT) {
            if ("branch-wins".equals(plan.policy)) return "branch";
            if ("main-wins".equals(plan.policy)) return "main";
            return "none";
        }
        if (item.kind == Kind.CONVERGED) return "converged";
        if (item.kind == Kind.APPLY) return "branch";
        return "skip";
    }

    private static String show(String hash) {
        if (hash == null || hash.isEmpty()) return "";
        if (hash.startsWith("sha256:")) return hash;
        return "sha256:" + hash;
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static Map<String, String> base(Connection connection, String table) throws SQLException {
        Map<String, String> rows = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pk, base_hash FROM sbx._pkb_base WHERE tbl = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.put(result.getString(1), result.getString(2) == null ? "" : result.getString(2));
                }
            }
        }
        return rows;
    }

    private static Map<String, StoreFiles.Row> files(Connection connection, String relation) throws SQLException {
        Map<String, StoreFiles.Row> rows = new LinkedHashMap<>();
        String sql = "SELECT path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, "
                + "db_edited, git_blob, binary_ref, byte_size, location, git_commit FROM " + relation;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                StoreFiles.Row row = StoreFiles.read(result);
                rows.put(row.path(), row);
            }
        }
        return rows;
    }

    private static Map<String, Entity> entities(Connection connection, String relation) throws SQLException {
        Map<String, Entity> rows = new LinkedHashMap<>();
        String sql = "SELECT entity_type, entity_id, version, payload_json, secret_refs, source_file, content_hash, updated_at FROM "
                + relation;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                Entity entity = new Entity(
                        result.getString(1),
                        result.getString(2),
                        result.getInt(3),
                        result.getString(4) == null ? "" : result.getString(4),
                        result.getString(5) == null ? "" : result.getString(5),
                        result.getString(6),
                        result.getString(7) == null ? "" : result.getString(7),
                        result.getString(8) == null ? "" : result.getString(8));
                rows.put(entity.type + "#" + entity.id, entity);
            }
        }
        return rows;
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : "";
        }
    }

    enum Kind { SKIP, APPLY, CONVERGED, CONFLICT }

    private static final class Plan {
        final String sandbox;
        final String policy;
        final boolean dryRun;
        final List<Item> items = new ArrayList<>();
        String createdFrom = "";
        int wouldApply;
        int converged;
        int conflictCount;
        int writes;
        int applied;
        boolean blocked;

        Plan(String sandbox, String policy, boolean dryRun) {
            this.sandbox = sandbox;
            this.policy = policy;
            this.dryRun = dryRun;
        }
    }

    private record Item(
            String table,
            String key,
            String base,
            String mainHash,
            String branchHash,
            long size,
            Kind kind,
            StoreFiles.Row mainFile,
            StoreFiles.Row branchFile,
            Entity mainEntity,
            Entity branchEntity
    ) {
    }

    private record Entity(
            String type,
            String id,
            int version,
            String payload,
            String secretRefs,
            String sourceFile,
            String contentHash,
            String updatedAt
    ) {
        String identity() {
            return StoreHashes.entityIdentity(payload, secretRefs, contentHash);
        }
    }
}
