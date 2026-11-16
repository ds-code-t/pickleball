package tools.dscode.store;

import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlConnections;
import tools.dscode.store.sql.SqlSession;
import tools.dscode.store.sql.SqlStore;
import tools.dscode.store.sql.SqliteOpen;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code pickleball store sandbox}. Each sandbox is its own SQLite file. Nothing in it points at main. */
public final class StoreSandbox {
    private StoreSandbox() {
    }

    public static int execute(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        String action = tokens.size() < 2 || tokens.get(1).startsWith("--") ? "" : tokens.get(1);
        boolean json = tokens.contains("--json");
        try {
            return switch (action) {
                case "create" -> create(project, tokens, out);
                case "list" -> list(project, json, out);
                case "use" -> use(project, tokens, out);
                case "diff" -> diff(project, tokens, json, out);
                case "extract" -> extract(project, tokens, out);
                case "drop" -> drop(project, tokens, out);
                case "prune" -> prune(project, tokens, out);
                default -> {
                    err.println("error: Unknown Store sandbox command: " + action);
                    err.println("fix: store sandbox create|list|use|diff|extract|drop|prune");
                    yield 2;
                }
            };
        } catch (IllegalArgumentException | IllegalStateException failure) {
            printFailure(err, failure.getMessage(), "store sandbox " + action);
            return 1;
        } catch (Exception failure) {
            printFailure(err, failure.getMessage(), "store sandbox " + action);
            return 1;
        }
    }

    private static int create(Path project, List<String> tokens, PrintStream out) throws Exception {
        String name = positional(tokens, 2, "sandbox create needs a name");
        Binding binding = Binding.sandbox(name);
        String suppliedOwner = value(tokens, "--owner=");
        String owner = suppliedOwner == null || suppliedOwner.isBlank()
                ? System.getProperty("user.name", "local") : suppliedOwner;
        String suppliedTtl = value(tokens, "--ttl=");
        String ttl = suppliedTtl == null || suppliedTtl.isBlank() ? "24h" : suppliedTtl;
        parseDuration(ttl);
        String from = value(tokens, "--from=");
        if (from != null && !from.isBlank() && !"main".equals(from)) {
            throw new IllegalArgumentException(
                    "sandbox --from must be main\nfix: store sandbox create " + name
                            + " from main, or extract a sandbox and hydrate it");
        }
        if (Files.isRegularFile(StorePaths.sandboxDatabase(project, name))) {
            throw new IllegalStateException("sandbox already exists: " + name + "\nfix: store sandbox list");
        }
        StoreGit.Snapshot git = StoreGit.capture(project);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("owner", owner);
        meta.put("ttl", ttl);
        meta.put("created_from_commit", git.commit() == null ? "" : git.commit());
        meta.put("created_from_dirty", git.dirty() ? "1" : "0");
        Path lockFile = StorePaths.root(project).resolve("locks").resolve("sandboxes.lock");
        try (FileLockHold ignored = lockFile(lockFile)) {
            if (Files.isRegularFile(StorePaths.sandboxDatabase(project, name))) {
                throw new IllegalStateException("sandbox already exists: " + name + "\nfix: store sandbox list");
            }
            try (SqlStore store = SqlStore.open(project, Binding.main())) {
                store.createBranch(name, meta);
            }
        }
        StoreLog.log(project, "sandbox", name, name, "create owner=" + owner + " ttl=" + ttl);
        out.println("sandbox " + name + " branch " + binding.branch());
        return 0;
    }

    private static int list(Path project, boolean json, PrintStream out) throws Exception {
        List<Row> rows = rows(project);
        if (json) {
            StringBuilder body = new StringBuilder();
            body.append("{\"command\":\"sandbox list\",\"count\":").append(rows.size()).append(",\"sandboxes\":[");
            for (int index = 0; index < rows.size(); index++) {
                if (index > 0) body.append(',');
                Row row = rows.get(index);
                body.append("{\"name\":\"").append(escape(row.name())).append("\",\"owner\":\"")
                        .append(escape(row.owner())).append("\",\"baseCommit\":\"").append(escape(row.base()))
                        .append("\",\"created\":\"").append(escape(row.created())).append("\",\"lastUsed\":\"")
                        .append(escape(row.lastUsed())).append("\",\"ttl\":\"").append(escape(row.ttl()))
                        .append("\",\"inUse\":").append(row.inUse()).append('}');
            }
            body.append("]}");
            out.println(body);
            return 0;
        }
        out.println("sandboxes " + rows.size());
        for (Row row : rows) {
            out.println(row.name() + " owner=" + row.owner() + " base=" + row.base()
                    + " created=" + row.created() + " lastUsed=" + row.lastUsed() + " ttl=" + row.ttl()
                    + " inUse=" + row.inUse());
        }
        return 0;
    }

    private static int use(Path project, List<String> tokens, PrintStream out) throws Exception {
        String name = positional(tokens, 2, "sandbox use needs a name");
        Binding.sandbox(name);
        require(project, name);
        touch(project, name);
        out.println("export PKB_STORE_SANDBOX=" + name);
        out.println("$env:PKB_STORE_SANDBOX='" + name + "'");
        out.println("set PKB_STORE_SANDBOX=" + name);
        StoreLog.log(project, "sandbox", name, name, "use");
        return 0;
    }

    private static int diff(Path project, List<String> tokens, boolean json, PrintStream out) throws Exception {
        String name = positional(tokens, 2, "sandbox diff needs a name");
        Binding.sandbox(name);
        require(project, name);
        touch(project, name);
        String only = value(tokens, "--table=");
        List<String> tables = only == null || only.isBlank()
                ? List.of("files", "entities")
                : List.of(only);
        List<String> lines = new ArrayList<>();
        try (SqlStore store = SqlStore.open(project, Binding.sandbox(name))) {
            SqlSession session = store.session();
            for (String table : tables) {
                if (!table.equals("files") && !table.equals("entities")) {
                    throw new IllegalArgumentException("unknown diff table: " + table);
                }
                lines.addAll(diffTable(session, table));
            }
        }
        StoreLog.log(project, "sandbox", name, name, "diff " + lines.size());
        if (json) {
            StringBuilder body = new StringBuilder();
            body.append("{\"command\":\"sandbox diff\",\"name\":\"").append(escape(name))
                    .append("\",\"count\":").append(lines.size()).append(",\"rows\":[");
            for (int index = 0; index < lines.size(); index++) {
                if (index > 0) body.append(',');
                body.append('"').append(escape(lines.get(index))).append('"');
            }
            body.append("]}");
            out.println(body);
            return 0;
        }
        out.println("diff " + name + " " + lines.size());
        for (String line : lines) out.println(line);
        return 0;
    }

    private static List<String> diffTable(SqlSession session, String table) throws SQLException {
        Map<String, String> base = new LinkedHashMap<>();
        try (var statement = session.connection().prepareStatement(
                "SELECT pk, base_hash FROM _pkb_base WHERE tbl = ? ORDER BY pk")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) {
                while (result.next()) base.put(result.getString(1), result.getString(2) == null ? "" : result.getString(2));
            }
        }
        Map<String, String> current = new LinkedHashMap<>();
        String sql = "files".equals(table)
                ? "SELECT path, content_hash FROM files ORDER BY path"
                : "SELECT entity_type || '#' || entity_id, content_hash FROM entities ORDER BY 1";
        try (var statement = session.connection().createStatement();
             var result = statement.executeQuery(sql)) {
            while (result.next()) {
                current.put(result.getString(1), result.getString(2) == null ? "" : result.getString(2));
            }
        }
        List<String> lines = new ArrayList<>();
        for (var entry : current.entrySet()) {
            String key = entry.getKey();
            if (!base.containsKey(key)) lines.add(table + " insert " + key + " " + entry.getValue());
            else if (!base.get(key).equals(entry.getValue())) lines.add(table + " update " + key + " " + entry.getValue());
        }
        for (var entry : base.entrySet()) {
            if (!current.containsKey(entry.getKey())) lines.add(table + " delete " + entry.getKey() + " " + entry.getValue());
        }
        return lines;
    }

    private static int extract(Path project, List<String> tokens, PrintStream out) throws Exception {
        String name = positional(tokens, 2, "sandbox extract needs a name");
        Binding.sandbox(name);
        String to = value(tokens, "--to=");
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("sandbox extract needs --to\nfix: store sandbox extract " + name + " --to=<dir>");
        }
        require(project, name);
        Path dest = Path.of(to);
        if (!dest.isAbsolute()) dest = project.resolve(dest);
        dest = dest.toAbsolutePath().normalize();
        Path root = project.toAbsolutePath().normalize();
        if (dest.equals(root) && !tokens.contains("--into-project")) {
            throw new IllegalArgumentException("extract into the project root needs --into-project\nfix: store sandbox extract "
                    + name + " --to=<dir>");
        }
        int written = 0;
        List<PlannedFile> planned = new ArrayList<>();
        try (SqlStore store = SqlStore.open(project, Binding.sandbox(name))) {
            try (var statement = store.session().connection().createStatement();
                 var rows = statement.executeQuery(
                         "SELECT path, raw_content, binary_ref, content_hash FROM files ORDER BY path")) {
                while (rows.next()) {
                    String path = rows.getString(1);
                    byte[] body = rows.getBytes(2);
                    boolean reference = rows.getInt(3) != 0;
                    String hash = rows.getString(4);
                    if (path == null) continue;
                    if (reference) {
                        noticeReference(project, path, hash, out);
                        continue;
                    }
                    planned.add(planFile(dest, path, body));
                }
            }
        }
        List<WrittenFile> writtenFiles = new ArrayList<>();
        List<Path> createdDirectories = new ArrayList<>();
        try {
            for (PlannedFile item : planned) {
                writtenFiles.add(writeFile(item, createdDirectories));
                written++;
            }
        } catch (IOException failure) {
            restore(writtenFiles, createdDirectories);
            throw failure;
        }
        touch(project, name);
        StoreLog.log(project, "sandbox", name, name, "extract files=" + written);
        out.println("extracted " + name + " files=" + written + " to=" + dest);
        return 0;
    }

    private static void noticeReference(Path project, String path, String hash, PrintStream out) throws IOException {
        Path disk = project.resolve(path);
        byte[] existing = Files.isRegularFile(disk) ? Files.readAllBytes(disk) : new byte[0];
        if (!StoreHashes.sha256(existing).equals(hash == null ? "" : hash)) {
            out.println("binary_ref_changed " + path);
        }
    }

    /** A refused path is decided before any destination file is created or replaced. */
    private static PlannedFile planFile(Path dest, String path, byte[] body) {
        String relative;
        try {
            relative = StorePaths.normalizeRelative(path);
        } catch (IllegalArgumentException refused) {
            throw new IllegalArgumentException(
                    "extract refused " + path + "\nfix: store a relative path without ..");
        }
        Path file = dest.resolve(relative).normalize();
        if (!file.startsWith(dest)) {
            throw new IllegalArgumentException(
                    "extract refused " + path + "\nfix: store a relative path without ..");
        }
        return new PlannedFile(file, body == null ? new byte[0] : body);
    }

    /**
     * Writes through a sibling temp file and a move, so a failed write never leaves a
     * partial destination file. Directories this call creates are recorded for restore.
     */
    private static WrittenFile writeFile(PlannedFile item, List<Path> createdDirectories) throws IOException {
        boolean existed = Files.isRegularFile(item.file());
        byte[] previous = existed ? Files.readAllBytes(item.file()) : null;
        Path parent = item.file().getParent();
        if (parent != null) {
            List<Path> missing = new ArrayList<>();
            for (Path cursor = parent; cursor != null && !Files.exists(cursor); cursor = cursor.getParent()) {
                missing.add(0, cursor);
            }
            for (Path directory : missing) {
                Files.createDirectory(directory);
                createdDirectories.add(directory);
            }
        }
        Path temp = item.file().resolveSibling(item.file().getFileName() + ".pkb-extract.tmp");
        try {
            Files.write(temp, item.body());
            Files.move(temp, item.file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return new WrittenFile(item.file(), previous, existed);
    }

    private static void restore(List<WrittenFile> written, List<Path> createdDirectories) {
        for (int index = written.size() - 1; index >= 0; index--) {
            WrittenFile item = written.get(index);
            try {
                if (item.existed()) Files.write(item.file(), item.previous());
                else Files.deleteIfExists(item.file());
            } catch (IOException ignored) {
                // The original write failure is the one the caller needs.
            }
        }
        for (int index = createdDirectories.size() - 1; index >= 0; index--) {
            try {
                Files.deleteIfExists(createdDirectories.get(index));
            } catch (IOException ignored) {
                // A directory that is not empty was not only ours.
            }
        }
    }

    private record PlannedFile(Path file, byte[] body) {
    }

    private record WrittenFile(Path file, byte[] previous, boolean existed) {
    }

    private static int drop(Path project, List<String> tokens, PrintStream out) throws Exception {
        String name = positional(tokens, 2, "sandbox drop needs a name");
        Binding binding = Binding.sandbox(name);
        require(project, name);
        boolean force = tokens.contains("--force");
        if (SqlConnections.branchInUse(project, binding.branch()) && !force) {
            throw new IllegalStateException("sandbox " + name + " is in use\nfix: store sandbox drop " + name + " --force");
        }
        SqlConnections.closeBranch(project, binding.branch());
        if (!deleteSandbox(project, name)) {
            out.println("pending-delete " + name);
            StoreLog.log(project, "sandbox", name, name, "drop pending-delete");
            return 0;
        }
        StoreLog.log(project, "sandbox", name, name, "drop force=" + force);
        out.println("dropped " + name);
        return 0;
    }

    private static int prune(Path project, List<String> tokens, PrintStream out) throws Exception {
        boolean dry = tokens.contains("--dry-run");
        boolean history = tokens.contains("--with-history");
        boolean vacuum = tokens.contains("--vacuum");
        String owner = value(tokens, "--owner=");
        String older = value(tokens, "--older-than=");
        Duration olderThan = older == null ? null : parseDuration(older);
        Instant now = Instant.now();
        retryPending(project, out, dry);
        List<Row> victims = new ArrayList<>();
        for (Row row : rows(project)) {
            if (owner != null && !owner.equals(row.owner())) continue;
            Instant used = Instant.parse(row.lastUsed());
            boolean expired = used.plus(parseDuration(row.ttl())).isBefore(now);
            if (olderThan != null) expired = used.isBefore(now.minus(olderThan));
            if (expired) victims.add(row);
        }
        if (dry) {
            for (Row row : victims) out.println("dry-run drop " + row.name());
            out.println("dry-run " + victims.size());
            return 0;
        }
        int dropped = 0;
        for (Row row : victims) {
            Binding binding = Binding.sandbox(row.name());
            if (SqlConnections.branchInUse(project, binding.branch())) {
                out.println("skipped " + row.name() + " in use");
                StoreLog.log(project, "sandbox", row.name(), row.name(), "prune skipped in use");
                continue;
            }
            if (history) deleteHistory(project, row.name());
            SqlConnections.closeBranch(project, binding.branch());
            if (!deleteSandbox(project, row.name())) {
                out.println("pending-delete " + row.name());
                StoreLog.log(project, "sandbox", row.name(), row.name(), "prune pending-delete");
                continue;
            }
            out.println("dropped " + row.name());
            StoreLog.log(project, "sandbox", row.name(), row.name(), history ? "prune history" : "prune");
            dropped++;
        }
        if (vacuum || freePageRatio(project) > 0.25d) {
            vacuumMain(project);
            out.println("vacuum " + dropped);
        }
        return 0;
    }

    private static void retryPending(Path project, PrintStream out, boolean dry) throws Exception {
        Path root = StorePaths.root(project).resolve("sandboxes");
        if (!Files.isDirectory(root)) return;
        List<Path> homes;
        try (var stream = Files.list(root)) {
            homes = stream.filter(dir -> Files.isRegularFile(dir.resolve("pending-delete"))).toList();
        }
        for (Path home : homes) {
            String name = home.getFileName().toString();
            if (!name.matches("[a-z0-9][a-z0-9-]{0,47}")) continue;
            if (dry) {
                out.println("dry-run drop " + name);
                continue;
            }
            if (SqlConnections.branchInUse(project, Binding.sandbox(name).branch())) {
                out.println("pending-delete " + name);
                continue;
            }
            SqlConnections.closeBranch(project, Binding.sandbox(name).branch());
            if (deleteSandbox(project, name)) out.println("dropped " + name);
            else out.println("pending-delete " + name);
        }
    }

    private static void deleteHistory(Path project, String name) throws Exception {
        try (SqlStore store = SqlStore.open(project, Binding.main())) {
            SqlSession session = store.session();
            session.update("DELETE FROM runs WHERE sandbox = ?", name);
            for (String table : List.of(
                    "run_logs", "scenario_executions", "step_executions",
                    "entity_usages", "log_entity_links", "artifacts")) {
                session.update("DELETE FROM " + table + " WHERE sandbox = ?", name);
            }
            session.update("DELETE FROM sandboxes WHERE name = ?", name);
        }
    }

    private static boolean deleteSandbox(Path project, String name) throws IOException {
        Path home = StorePaths.sandboxHome(project, name);
        Path database = home.resolve("source.db");
        boolean free = tools.dscode.store.sql.StoreFiles.awaitRelease(database, tools.dscode.store.sql.StoreFiles.RELEASE_MS,
                tools.dscode.store.sql.StoreFiles::renameProbe)
                && tools.dscode.store.sql.StoreFiles.awaitRelease(Path.of(database.toString() + "-wal"),
                tools.dscode.store.sql.StoreFiles.RELEASE_MS, tools.dscode.store.sql.StoreFiles::renameProbe)
                && tools.dscode.store.sql.StoreFiles.awaitRelease(Path.of(database.toString() + "-shm"),
                tools.dscode.store.sql.StoreFiles.RELEASE_MS, tools.dscode.store.sql.StoreFiles::renameProbe);
        if (!free) {
            Files.createDirectories(home);
            Files.writeString(home.resolve("pending-delete"), database.toAbsolutePath().normalize() + "\n");
            return false;
        }
        deleteTree(home);
        return true;
    }

    private static double freePageRatio(Path project) {
        Path database = StorePaths.mainDatabase(project);
        if (!Files.isRegularFile(database)) return 0;
        try (Connection connection = SqliteOpen.open(database);
             Statement statement = connection.createStatement()) {
            long pages = pragma(statement, "page_count");
            long free = pragma(statement, "freelist_count");
            if (pages <= 0) return 0;
            return (double) free / (double) pages;
        } catch (SQLException failure) {
            return 0;
        }
    }

    private static long pragma(Statement statement, String name) throws SQLException {
        try (ResultSet rows = statement.executeQuery("PRAGMA " + name)) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    private static void vacuumMain(Path project) throws Exception {
        Path database = StorePaths.mainDatabase(project);
        if (!Files.isRegularFile(database)) return;
        SqlConnections.closeBranch(project, Binding.main().branch());
        try (Connection connection = SqliteOpen.open(database);
             Statement statement = connection.createStatement()) {
            statement.execute("VACUUM");
        }
    }

    private static List<Row> rows(Path project) throws Exception {
        Path root = StorePaths.root(project).resolve("sandboxes");
        if (!Files.isDirectory(root)) return List.of();
        List<Row> rows = new ArrayList<>();
        List<Path> homes;
        try (var stream = Files.list(root)) {
            homes = stream.filter(dir -> Files.isRegularFile(dir.resolve("source.db"))).toList();
        }
        for (Path home : homes) {
            String name = home.getFileName().toString();
            if (!name.matches("[a-z0-9][a-z0-9-]{0,47}")) continue;
            rows.add(readRow(project, name));
        }
        rows.sort(Comparator.comparing(Row::name));
        return rows;
    }

    private static Row readRow(Path project, String name) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        try (SqlStore store = SqlStore.open(project, Binding.sandbox(name));
             var statement = store.session().connection().createStatement();
             var result = statement.executeQuery("SELECT `key`, value FROM _pkb_branch")) {
            while (result.next()) values.put(result.getString(1), result.getString(2) == null ? "" : result.getString(2));
        }
        String created = values.getOrDefault("created_at", "");
        String used = values.getOrDefault("last_used", created);
        boolean inUse = SqlConnections.branchInUse(project, Binding.sandbox(name).branch());
        return new Row(
                name,
                values.getOrDefault("owner", ""),
                values.getOrDefault("created_from_commit", ""),
                created,
                used.isBlank() ? created : used,
                values.getOrDefault("ttl", "24h"),
                inUse);
    }

    private static void require(Path project, String name) {
        Binding.sandbox(name);
        if (!Files.isRegularFile(StorePaths.sandboxDatabase(project, name))) {
            throw new IllegalArgumentException("unknown sandbox " + name + "\nfix: store sandbox create " + name);
        }
    }

    private static void touch(Path project, String name) throws Exception {
        String now = Instant.now().toString();
        try (SqlStore store = SqlStore.open(project, Binding.sandbox(name))) {
            store.session().update(
                    "INSERT INTO _pkb_branch(`key`, value) VALUES ('last_used', ?) "
                            + "ON CONFLICT(`key`) DO UPDATE SET value = excluded.value",
                    now);
        }
    }

    static Duration parseDuration(String raw) {
        if (raw == null || raw.isBlank()) return Duration.ofHours(24);
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("p")) {
            return Duration.parse(raw.trim().toUpperCase(Locale.ROOT));
        }
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) throw new IllegalArgumentException("duration is not a number: " + raw);
        long number = Long.parseLong(digits);
        if (text.endsWith("d")) return Duration.ofDays(number);
        if (text.endsWith("h")) return Duration.ofHours(number);
        if (text.endsWith("m")) return Duration.ofMinutes(number);
        if (text.endsWith("s")) return Duration.ofSeconds(number);
        return Duration.ofHours(number);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) Files.deleteIfExists(path);
        }
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

    private static void printFailure(PrintStream err, String message, String fix) {
        String text = message == null || message.isBlank() ? "sandbox failed" : message;
        String error = text;
        String hint = fix;
        int split = text.indexOf('\n');
        if (split >= 0) {
            error = text.substring(0, split);
            String rest = text.substring(split + 1).trim();
            if (rest.startsWith("fix:")) hint = rest.substring(4).trim();
        }
        if (error.startsWith("error:")) error = error.substring(6).trim();
        err.println("error: " + error);
        err.println("fix: " + hint);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static FileLockHold lockFile(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock();
            return new FileLockHold(channel, lock);
        } catch (IOException failure) {
            channel.close();
            throw failure;
        }
    }

    private static final class FileLockHold implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private FileLockHold(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            try {
                if (lock != null && lock.isValid()) lock.release();
            } finally {
                channel.close();
            }
        }
    }

    private record Row(String name, String owner, String base, String created, String lastUsed, String ttl, boolean inUse) {
    }
}
