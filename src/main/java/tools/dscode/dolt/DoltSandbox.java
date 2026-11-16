package tools.dscode.dolt;

import tools.dscode.dolt.store.Binding;
import tools.dscode.dolt.store.DoltConnections;
import tools.dscode.dolt.store.DoltSession;
import tools.dscode.dolt.store.DoltStore;

import tools.dscode.dolt.store.DoltDialect;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** {@code pickleball dolt sandbox}. Metadata stays on main. Edits stay on {@code sbx-<name>}. */
public final class DoltSandbox {
    private static final Pattern COMMIT = Pattern.compile("[0-9a-zA-Z]{6,64}");

    private DoltSandbox() {
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
                    err.println("error: Unknown Dolt sandbox command: " + action);
                    err.println("fix: dolt sandbox create|list|use|diff|extract|drop|prune");
                    yield 2;
                }
            };
        } catch (IllegalArgumentException | IllegalStateException failure) {
            printFailure(err, failure.getMessage(), "dolt sandbox " + action);
            return 1;
        } catch (Exception failure) {
            printFailure(err, failure.getMessage(), "dolt sandbox " + action);
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
        if (from != null && !from.isBlank() && !COMMIT.matcher(from).matches()) {
            throw new IllegalArgumentException(
                    "sandbox --from must be a commit hash\nfix: dolt sandbox create " + name + " --from=<hash>");
        }
        String now = Instant.now().toString();
        try (DoltStore store = DoltStore.open(project, Binding.main())) {
            DoltSession session = store.session();
            withSandboxesLock(session, () -> {
                if (session.scalar("SELECT name FROM sandboxes WHERE name = ?", name) != null) {
                    throw new IllegalStateException("sandbox already exists: " + name + "\nfix: dolt sandbox list");
                }
                if (from == null || from.isBlank()) store.createBranch(name);
                else session.call("CALL DOLT_BRANCH(?, ?)", binding.branch(), from);
                String base = from == null || from.isBlank() ? head(session) : from;
                session.transaction("sandboxes", () -> {
                    session.script("SELECT name FROM sandboxes FOR UPDATE");
                    session.update(
                            "INSERT INTO sandboxes(name, owner, base_commit, created, last_used, ttl, sandbox) VALUES (?, ?, ?, ?, ?, ?, '')",
                            name, owner, base == null ? "" : base, now, now, ttl);
                });
            });
        }
        DoltLog.log(project, "sandbox", name, name, "create owner=" + owner + " ttl=" + ttl);
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
                        .append(escape(row.lastUsed())).append("\",\"ttl\":\"").append(escape(row.ttl())).append("\"}");
            }
            body.append("]}");
            out.println(body);
            return 0;
        }
        out.println("sandboxes " + rows.size());
        for (Row row : rows) {
            out.println(row.name() + " owner=" + row.owner() + " base=" + row.base()
                    + " created=" + row.created() + " lastUsed=" + row.lastUsed() + " ttl=" + row.ttl());
        }
        return 0;
    }

    private static int use(Path project, List<String> tokens, PrintStream out) throws Exception {
        String name = positional(tokens, 2, "sandbox use needs a name");
        Binding.sandbox(name);
        require(project, name);
        touch(project, name);
        out.println("export PKB_DOLT_SANDBOX=" + name);
        out.println("$env:PKB_DOLT_SANDBOX='" + name + "'");
        out.println("set PKB_DOLT_SANDBOX=" + name);
        DoltLog.log(project, "sandbox", name, name, "use");
        return 0;
    }

    private static int diff(Path project, List<String> tokens, boolean json, PrintStream out) throws Exception {
        String name = positional(tokens, 2, "sandbox diff needs a name");
        Binding binding = Binding.sandbox(name);
        require(project, name);
        touch(project, name);
        String only = value(tokens, "--table=");
        List<String> tables = only == null || only.isBlank()
                ? List.of("files", "entities", "feature_docs", "step_definitions", "scenarios", "steps")
                : List.of(only);
        List<String> lines = new ArrayList<>();
        try (DoltStore store = DoltStore.open(project, binding)) {
            DoltSession session = store.session();
            for (String table : tables) {
                if (!table.matches("[a-z_]+")) throw new IllegalArgumentException("unknown diff table: " + table);
                try {
                    List<String> values = session.column(diffSql(table));
                    for (String value : values) lines.add(table + " " + (value == null ? "" : value));
                } catch (java.sql.SQLException failure) {
                    // A table with no working diff has no rows. A missing diff table is the same.
                }
            }
        }
        DoltLog.log(project, "sandbox", name, name, "diff " + lines.size());
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

    private static int extract(Path project, List<String> tokens, PrintStream out) throws Exception {
        String name = positional(tokens, 2, "sandbox extract needs a name");
        Binding binding = Binding.sandbox(name);
        String to = value(tokens, "--to=");
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("sandbox extract needs --to\nfix: dolt sandbox extract " + name + " --to=<dir>");
        }
        require(project, name);
        Path dest = Path.of(to);
        if (!dest.isAbsolute()) dest = project.resolve(dest);
        dest = dest.toAbsolutePath().normalize();
        Path root = project.toAbsolutePath().normalize();
        if ((dest.equals(root) || root.startsWith(dest)) && !tokens.contains("--into-project") && dest.equals(root)) {
            throw new IllegalArgumentException("extract into the project root needs --into-project\nfix: dolt sandbox extract "
                    + name + " --to=<dir>");
        }
        int written = 0;
        List<PlannedFile> planned = new ArrayList<>();
        try (DoltStore store = DoltStore.open(project, binding)) {
            try (var statement = store.session().connection().createStatement();
                 var rows = statement.executeQuery("SELECT path, raw_content FROM files ORDER BY path")) {
                while (rows.next()) {
                    String path = rows.getString(1);
                    byte[] body = rows.getBytes(2);
                    if (path == null) continue;
                    planned.add(planFile(dest, path, body));
                }
            }
        }
        List<WrittenFile> writtenFiles = new ArrayList<>();
        try {
            for (PlannedFile item : planned) {
                writtenFiles.add(writeFile(item));
                written++;
            }
        } catch (java.io.IOException failure) {
            restore(writtenFiles);
            throw failure;
        }
        touch(project, name);
        DoltLog.log(project, "sandbox", name, name, "extract files=" + written);
        out.println("extracted " + name + " files=" + written + " to=" + dest);
        return 0;
    }

    /** A refused path is decided before any destination file is created or replaced. */
    private static PlannedFile planFile(Path dest, String path, byte[] body) {
        String relative;
        try {
            relative = DoltPaths.normalizeRelative(path);
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

    private static WrittenFile writeFile(PlannedFile item) throws java.io.IOException {
        boolean existed = Files.isRegularFile(item.file());
        byte[] previous = existed ? Files.readAllBytes(item.file()) : null;
        Path parent = item.file().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(item.file(), item.body());
        return new WrittenFile(item.file(), previous, existed);
    }

    private static void restore(List<WrittenFile> written) {
        for (int index = written.size() - 1; index >= 0; index--) {
            WrittenFile item = written.get(index);
            try {
                if (item.existed()) Files.write(item.file(), item.previous());
                else Files.deleteIfExists(item.file());
            } catch (java.io.IOException ignored) {
                // The original write failure is the one the caller needs.
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
        if (DoltConnections.branchInUse(project, binding.branch()) && !force) {
            throw new IllegalStateException("sandbox " + name + " is in use\nfix: dolt sandbox drop " + name + " --force");
        }
        if (force) DoltConnections.closeBranch(project, binding.branch());
        try (DoltStore store = DoltStore.open(project, Binding.main())) {
            DoltSession session = store.session();
            withSandboxesLock(session, () -> {
                session.call("CALL DOLT_BRANCH('-D', ?)", binding.branch());
                session.transaction("sandboxes", () -> {
                    session.scalar("SELECT name FROM sandboxes WHERE name = ? FOR UPDATE", name);
                    session.update("DELETE FROM sandboxes WHERE name = ?", name);
                });
            });
        }
        deleteTree(DoltPaths.sandboxHome(project, name));
        DoltLog.log(project, "sandbox", name, name, "drop force=" + force);
        out.println("dropped " + name);
        return 0;
    }

    private static int prune(Path project, List<String> tokens, PrintStream out) throws Exception {
        boolean dry = tokens.contains("--dry-run");
        boolean history = tokens.contains("--with-history");
        String owner = value(tokens, "--owner=");
        String older = value(tokens, "--older-than=");
        Duration olderThan = older == null ? null : parseDuration(older);
        Instant now = Instant.now();
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
            if (DoltConnections.branchInUse(project, binding.branch())) {
                out.println("skipped " + row.name() + " in use");
                DoltLog.log(project, "sandbox", row.name(), row.name(), "prune skipped in use");
                continue;
            }
            try (DoltStore store = DoltStore.open(project, Binding.main())) {
                DoltSession session = store.session();
                withSandboxesLock(session, () -> {
                    try {
                        session.call("CALL DOLT_BRANCH('-D', ?)", binding.branch());
                    } catch (SQLException failure) {
                        // A branch that is already gone still loses its metadata.
                    }
                    session.transaction("sandboxes", () -> {
                        session.update("DELETE FROM sandboxes WHERE name = ?", row.name());
                        if (history) {
                            session.update("DELETE FROM runs WHERE sandbox = ?", row.name());
                            for (String table : List.of(
                                    "run_logs", "scenario_executions", "step_executions",
                                    "entity_usages", "log_entity_links", "artifacts")) {
                                session.update("DELETE FROM " + table + " WHERE sandbox = ?", row.name());
                            }
                        }
                    });
                });
            }
            deleteTree(DoltPaths.sandboxHome(project, row.name()));
            out.println("dropped " + row.name());
            DoltLog.log(project, "sandbox", row.name(), row.name(), history ? "prune history" : "prune");
            dropped++;
        }
        try (DoltStore store = DoltStore.open(project, Binding.main())) {
            store.session().script("CALL DOLT_GC()");
        }
        out.println("gc " + dropped);
        return 0;
    }

    private static void withSandboxesLock(DoltSession session, DoltDialect.SqlTask work) throws Exception {
        String got = session.scalar("SELECT GET_LOCK('pkb:sandboxes', 30)");
        if (!"1".equals(got)) {
            throw new IllegalStateException("sandboxes lock was not acquired\nfix: retry dolt sandbox");
        }
        try {
            work.run();
        } finally {
            try {
                session.scalar("SELECT RELEASE_LOCK('pkb:sandboxes')");
            } catch (SQLException ignored) {
                // Closing the connection releases a leftover lock.
            }
        }
    }

    private static String diffSql(String table) {
        return switch (table) {
            case "files", "feature_docs", "step_definitions", "scenarios", "steps" ->
                    "SELECT to_path FROM dolt_diff_" + table + " WHERE to_commit = 'WORKING'";
            case "entities" ->
                    "SELECT CONCAT(to_entity_type, '#', to_entity_id) FROM dolt_diff_entities WHERE to_commit = 'WORKING'";
            default -> "SELECT to_commit FROM dolt_diff_" + table + " WHERE to_commit = 'WORKING'";
        };
    }

    private static List<Row> rows(Path project) throws Exception {
        if (!DoltPaths.ready(project)) return List.of();
        List<Row> rows = new ArrayList<>();
        try (DoltStore store = DoltStore.open(project, Binding.main())) {
            try (var statement = store.session().connection().prepareStatement(
                    "SELECT name, owner, base_commit, created, last_used, ttl FROM sandboxes ORDER BY name");
                 var result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new Row(
                            result.getString(1), result.getString(2), result.getString(3),
                            result.getString(4), result.getString(5), result.getString(6)));
                }
            }
        }
        rows.sort(Comparator.comparing(Row::name));
        return rows;
    }

    private static void require(Path project, String name) throws Exception {
        Binding.sandbox(name);
        if (!DoltPaths.ready(project)) {
            throw new IllegalArgumentException("unknown sandbox " + name + "\nfix: dolt sandbox create " + name);
        }
        try (DoltStore store = DoltStore.open(project, Binding.main())) {
            String found = store.session().scalar("SELECT name FROM sandboxes WHERE name = ?", name);
            if (found == null) {
                throw new IllegalArgumentException("unknown sandbox " + name + "\nfix: dolt sandbox create " + name);
            }
        }
    }

    private static void touch(Path project, String name) throws Exception {
        try (DoltStore store = DoltStore.open(project, Binding.main())) {
            store.session().update(
                    "UPDATE sandboxes SET last_used = ? WHERE name = ?",
                    Instant.now().toString(), name);
        }
    }

    private static String head(DoltSession session) {
        try {
            String hash = session.scalar("SELECT HASHOF('HEAD')");
            if (hash != null && !hash.isBlank()) return hash;
        } catch (java.sql.SQLException ignored) {
            // Older shapes use dolt_log.
        }
        try {
            return session.scalar("SELECT commit_hash FROM dolt_log LIMIT 1");
        } catch (java.sql.SQLException failure) {
            return "";
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

    private static void deleteTree(Path root) throws java.io.IOException {
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

    private record Row(String name, String owner, String base, String created, String lastUsed, String ttl) {
    }
}
