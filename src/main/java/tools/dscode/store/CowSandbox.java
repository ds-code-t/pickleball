package tools.dscode.store;

import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlConnections;
import tools.dscode.store.sql.SqlDialect;
import tools.dscode.store.sql.SqlStore;
import tools.dscode.store.sql.SqliteOpen;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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

/**
 * Copy-on-write sandbox. The file is still {@code sandboxes/<name>/source.db}.
 * The parent is named in {@code _pkb_branch} and is read with its own connection.
 * The run path does not {@code ATTACH} that parent.
 */
public final class CowSandbox {
    static final String MODE_COW = "cow";
    static final String META_MODE = "sandbox_mode";
    static final String META_PROMOTED = "head_promoted";

    private CowSandbox() {
    }

    public static boolean isCow(Connection connection) throws SQLException {
        return MODE_COW.equals(UniversalEntity.meta(connection, META_MODE));
    }

    /**
     * Creates an empty schema database. File bytes stay on the parent.
     * {@code parent} is a sandbox name, or {@code main}.
     */
    public static void create(Path project, String name, String parent, Map<String, String> meta)
            throws SQLException, IOException {
        Binding check = Binding.sandbox(name);
        String parentName = parent == null || parent.isBlank() ? "main" : parent;
        if (parentName.indexOf('/') >= 0 || parentName.indexOf('\\') >= 0 || parentName.contains(".db")) {
            throw new SQLException("error: sandbox parent must be a name");
        }
        Path target = StorePaths.sandboxDatabase(project, check.name());
        if (Files.exists(target)) {
            throw new SQLException("error: sandbox " + check.name() + " already exists");
        }
        Files.createDirectories(target.getParent());
        try (Connection opened = SqliteOpen.open(target)) {
            opened.createStatement().close();
        }
        try (SqlStore store = SqlStore.open(project, Binding.sandbox(check.name()))) {
            SqlDialect.ensureSchema(store.session());
            Connection connection = store.session().connection();
            String now = Instant.now().toString();
            putBranch(connection, "name", check.name());
            putBranch(connection, "created_at", now);
            putBranch(connection, "created_from_commit", text(meta, "created_from_commit"));
            putBranch(connection, "created_from_dirty", text(meta, "created_from_dirty").isBlank()
                    ? "0" : text(meta, "created_from_dirty"));
            putBranch(connection, "owner", text(meta, "owner"));
            String ttl = text(meta, "ttl");
            putBranch(connection, "ttl", ttl.isBlank() ? "24h" : ttl);
            putBranch(connection, "store_schema", "1");
            putBranch(connection, "last_used", now);
            putBranch(connection, "parent", parentName);
            UniversalEntity.putMeta(connection, META_MODE, MODE_COW);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
        }
        SqlConnections.closeBranch(project, Binding.sandbox(check.name()).branch());
    }

    /**
     * Records a delete delta for a row the child can see. Does not delete the
     * working-tree file. Returns false when neither the child nor the parent has the row.
     */
    public static boolean tombstoneFile(Connection connection, Path project, String path) throws SQLException {
        String before = UniversalEntity.currentPayload(connection, "files", path);
        boolean local = UniversalEntity.text(connection, "SELECT path FROM files WHERE path = ?", path) != null;
        boolean parent = !local && before == null && parentHasFile(project, connection, path);
        if (!local && before == null && !parent) return false;
        DeltaLog.append(connection, project, DeltaLog.DELETE, "files", path, before, null, false);
        return true;
    }

    public static int promote(Path project, List<String> tokens, PrintStream out, PrintStream err) throws Exception {
        String name = positional(tokens, 2, "sandbox promote needs a name");
        String to = value(tokens, "--to=");
        String logs = value(tokens, "--logs=");
        if (to == null || (!"parent".equals(to) && !"top".equals(to))) {
            err.println("error: sandbox promote needs --to=parent|top");
            err.println("fix: store sandbox promote " + name + " --to=parent|top --logs=clear|archive");
            return 2;
        }
        if (logs == null || (!"clear".equals(logs) && !"archive".equals(logs))) {
            err.println("error: sandbox promote needs --logs=clear|archive");
            err.println("fix: store sandbox promote " + name + " --to=parent|top --logs=clear|archive");
            return 2;
        }
        Binding.sandbox(name);
        if (!Files.isRegularFile(StorePaths.sandboxDatabase(project, name))) {
            throw new IllegalArgumentException("unknown sandbox " + name + "\nfix: store sandbox create " + name);
        }
        List<Change> changes;
        String parentName;
        try (SqlStore child = SqlStore.open(project, Binding.sandbox(name))) {
            Connection connection = child.session().connection();
            if (!isCow(connection)) {
                throw new IllegalArgumentException(
                        "sandbox " + name + " is not copy-on-write\nfix: store sandbox create " + name + " --mode=cow");
            }
            parentName = branchValue(connection, "parent");
            changes = changes(connection);
        }
        if (parentName == null || parentName.isBlank()) {
            throw new IllegalStateException("sandbox " + name + " has no parent\nfix: store sandbox create " + name + " --mode=cow");
        }
        String destination = "top".equals(to) ? walkToMain(project, parentName) : parentName;
        Binding destBinding = "main".equals(destination) ? Binding.main() : Binding.sandbox(destination);
        if (!destBinding.primary() && !Files.isRegularFile(StorePaths.sandboxDatabase(project, destination))) {
            throw new IllegalArgumentException("unknown sandbox " + destination + "\nfix: store sandbox list");
        }
        List<String> conflicts = new ArrayList<>();
        List<String> sync = new ArrayList<>();
        int deltas = 0;
        try (SqlStore dest = SqlStore.open(project, destBinding)) {
            Connection connection = dest.session().connection();
            for (Change change : changes) {
                boolean file = "files".equals(change.table());
                if (file && !change.tombstone() && destBinding.primary() && bothSides(project, connection, change.pk())) {
                    conflicts.add(change.pk());
                    continue;
                }
                String before = UniversalEntity.currentPayload(connection, change.table(), change.pk());
                if (file && !change.tombstone() && destBinding.primary()) {
                    byte[] body = change.payload() == null ? new byte[0] : change.payload().getBytes(StandardCharsets.UTF_8);
                    StoreContent.write(connection, project, change.pk(), StoreSources.sourceType(change.pk()), body,
                            Instant.now().toString());
                    sync.add(change.pk());
                }
                String after = change.tombstone() ? null : change.payload();
                String kind = change.tombstone() ? DeltaLog.DELETE : DeltaLog.PUT;
                DeltaLog.append(connection, project, kind, change.table(), change.pk(), before, after, true);
                deltas++;
            }
        }
        if (!sync.isEmpty()) {
            Binding remembered = StoreScope.current();
            StoreScope.clear();
            try {
                StoreExtractor.Result result = StoreExtractor.extract(
                        project, new StoreExtractor.Options(sync, false));
                for (String conflict : result.conflictPaths()) {
                    if (!conflicts.contains(conflict)) conflicts.add(conflict);
                }
            } finally {
                if (remembered != null && !remembered.primary()) StoreScope.bind(remembered);
            }
        }
        applyLogs(project, name, logs);
        try (SqlStore child = SqlStore.open(project, Binding.sandbox(name))) {
            UniversalEntity.putMeta(child.session().connection(), META_PROMOTED, "1");
        }
        StoreLog.log(project, "sandbox", name, name,
                "promote to=" + destination + " logs=" + logs + " deltas=" + deltas + " conflicts=" + conflicts.size());
        boolean json = tokens.contains("--json");
        if (json) {
            out.println(json(name, destination, logs, deltas, conflicts));
        } else {
            out.println("promoted " + name + " to=" + destination + " deltas=" + deltas + " conflicts=" + conflicts.size());
            for (String conflict : conflicts) out.println("conflict: " + conflict);
        }
        return conflicts.isEmpty() ? 0 : 1;
    }

    /** Copies the parent head into the child. Child deltas applied afterward overlay these rows. */
    static void seedParentHead(Connection child, Path project) throws SQLException {
        if (project == null || !isCow(child)) return;
        String parent = branchValue(child, "parent");
        if (parent == null || parent.isBlank()) return;
        Path parentFile = "main".equals(parent)
                ? StorePaths.mainDatabase(project)
                : StorePaths.sandboxDatabase(project, parent);
        if (!Files.isRegularFile(parentFile)) return;
        String url = child.getMetaData().getURL();
        String absolute = parentFile.toAbsolutePath().toString();
        if (url != null && url.contains(absolute)) return;
        try (Connection parentConnection = SqliteOpen.open(parentFile);
             PreparedStatement read = parentConnection.prepareStatement(
                     "SELECT tbl, pk, payload_json, tombstone FROM head_rows WHERE head_id = ?")) {
            read.setString(1, UniversalEntity.HEAD_ID);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    UniversalEntity.change(child, """
                            INSERT INTO head_rows(head_id, tbl, pk, payload_json, tombstone)
                            VALUES (?, ?, ?, ?, ?)
                            ON CONFLICT(head_id, tbl, pk) DO NOTHING
                            """, UniversalEntity.HEAD_ID, rows.getString(1), rows.getString(2),
                            rows.getString(3), rows.getInt(4));
                }
            }
        }
    }

    private static boolean parentHasFile(Path project, Connection child, String path) throws SQLException {
        String parent = branchValue(child, "parent");
        if (parent == null || parent.isBlank() || project == null) return false;
        Path parentFile = "main".equals(parent)
                ? StorePaths.mainDatabase(project)
                : StorePaths.sandboxDatabase(project, parent);
        if (!Files.isRegularFile(parentFile)) return false;
        try (Connection parentConnection = SqliteOpen.open(parentFile);
             PreparedStatement statement = parentConnection.prepareStatement(
                     "SELECT 1 FROM files WHERE path = ?")) {
            statement.setString(1, path);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static boolean bothSides(Path project, Connection connection, String path) throws SQLException {
        StoreFiles.Row row = StoreFiles.find(connection, path);
        if (row == null || !row.edited()) return false;
        Path disk = project.resolve(path);
        if (!Files.isRegularFile(disk)) return false;
        String synced = row.lastSyncedHash() == null ? "" : row.lastSyncedHash();
        if (synced.isBlank()) return false;
        try {
            String diskHash = StoreHashes.sha256(Files.readAllBytes(disk));
            return !diskHash.equals(synced);
        } catch (IOException failure) {
            throw new SQLException(failure.getMessage(), failure);
        }
    }

    private static List<Change> changes(Connection connection) throws SQLException {
        Map<String, Change> latest = new LinkedHashMap<>();
        for (DeltaLog.Delta delta : DeltaLog.after(connection, 0L)) {
            latest.put(delta.table() + "\n" + delta.pk(), new Change(delta.table(), delta.pk(), null, false));
        }
        List<Change> resolved = new ArrayList<>();
        for (Change key : latest.values()) {
            String payload = null;
            boolean tombstone = false;
            boolean found = false;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT payload_json, tombstone FROM head_rows
                    WHERE head_id = ? AND tbl = ? AND pk = ?
                    """)) {
                statement.setString(1, UniversalEntity.HEAD_ID);
                statement.setString(2, key.table());
                statement.setString(3, key.pk());
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        found = true;
                        tombstone = rows.getInt(2) != 0;
                        payload = tombstone ? null : rows.getString(1);
                    }
                }
            }
            if (!found) {
                DeltaLog.Delta last = null;
                for (DeltaLog.Delta delta : DeltaLog.after(connection, 0L)) {
                    if (key.table().equals(delta.table()) && key.pk().equals(delta.pk())) last = delta;
                }
                if (last == null) continue;
                tombstone = last.after() == null;
                payload = last.after();
            }
            resolved.add(new Change(key.table(), key.pk(), payload, tombstone));
        }
        return resolved;
    }

    private static String walkToMain(Path project, String parentName) throws SQLException {
        String cursor = parentName;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (cursor != null && !"main".equals(cursor) && seen.add(cursor)) {
            Path file = StorePaths.sandboxDatabase(project, cursor);
            if (!Files.isRegularFile(file)) return "main";
            String next;
            try (Connection connection = SqliteOpen.open(file)) {
                next = branchValue(connection, "parent");
            }
            if (next == null || next.isBlank() || "main".equals(next)) return "main";
            cursor = next;
        }
        return "main".equals(cursor) ? "main" : parentName;
    }

    private static void applyLogs(Path project, String name, String logs) throws SQLException {
        Path runstate = StorePaths.runState(project);
        if (!Files.isRegularFile(runstate)) return;
        try (Connection connection = SqliteOpen.open(runstate)) {
            if ("clear".equals(logs)) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM run_logs WHERE sandbox = ?")) {
                    statement.setString(1, name);
                    statement.executeUpdate();
                }
                return;
            }
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT run_id, note FROM runs WHERE sandbox = ?")) {
                statement.setString(1, name);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(new String[] {result.getString(1), result.getString(2)});
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE runs SET note = ? WHERE run_id = ?")) {
                for (String[] row : rows) {
                    String note = row[1] == null ? "" : row[1];
                    if (note.startsWith("archived")) continue;
                    String prefixed = note.isEmpty() ? "archived" : "archived " + note;
                    update.setString(1, prefixed);
                    update.setString(2, row[0]);
                    update.executeUpdate();
                }
            }
        }
    }

    private static void putBranch(Connection connection, String key, String value) throws SQLException {
        UniversalEntity.change(connection, """
                INSERT INTO _pkb_branch(`key`, value) VALUES (?, ?)
                ON CONFLICT(`key`) DO UPDATE SET value = excluded.value
                """, key, value == null ? "" : value);
    }

    private static String branchValue(Connection connection, String key) throws SQLException {
        return UniversalEntity.text(connection, "SELECT value FROM _pkb_branch WHERE `key` = ?", key);
    }

    private static String text(Map<String, String> meta, String key) {
        if (meta == null) return "";
        String value = meta.get(key);
        return value == null ? "" : value;
    }

    private static String json(String name, String destination, String logs, int deltas, List<String> conflicts) {
        StringBuilder body = new StringBuilder();
        body.append("{\"command\":\"sandbox promote\",\"name\":\"").append(escape(name))
                .append("\",\"to\":\"").append(escape(destination))
                .append("\",\"logs\":\"").append(escape(logs))
                .append("\",\"deltas\":").append(deltas)
                .append(",\"conflicts\":[");
        for (int index = 0; index < conflicts.size(); index++) {
            if (index > 0) body.append(',');
            body.append('"').append(escape(conflicts.get(index))).append('"');
        }
        body.append("]}");
        return body.toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String positional(List<String> tokens, int index, String message) {
        if (index >= tokens.size() || tokens.get(index).startsWith("--")) {
            throw new IllegalArgumentException(message);
        }
        return tokens.get(index);
    }

    private static String value(List<String> tokens, String prefix) {
        for (String token : tokens) {
            if (token != null && token.startsWith(prefix)) return token.substring(prefix.length());
        }
        return null;
    }

    private record Change(String table, String pk, String payload, boolean tombstone) {
    }
}
