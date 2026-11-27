package tools.dscode.store;

import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlConnections;
import tools.dscode.store.sql.SqlStore;
import tools.dscode.store.sql.SqliteOpen;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code store branch}. Fork copies a finished run's pin into a cow sandbox.
 * Discard deletes an empty fork. Promote, link, and tree are later phases.
 * The page posts these verbs. It does not open SQLite.
 */
public final class StoreBranch {
    private StoreBranch() {
    }

    public static int execute(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        String action = tokens.size() < 2 || tokens.get(1).startsWith("--") ? "" : tokens.get(1);
        try {
            return switch (action) {
                case "fork" -> fork(project, tokens, out, err);
                case "discard" -> discard(project, tokens, out, err);
                default -> {
                    err.println("error: Unknown Store branch command: " + (action.isEmpty() ? "branch" : action));
                    err.println("fix: store branch fork --from-run=<runId> [--name=<name>]");
                    yield 2;
                }
            };
        } catch (RuntimeException failure) {
            return failed(err, failure.getMessage(), "store branch fork --from-run=<runId> [--name=<name>]");
        } catch (Exception failure) {
            return failed(err, failure.getMessage(), "store branch fork --from-run=<runId> [--name=<name>]");
        }
    }

    private static int fork(Path project, List<String> tokens, PrintStream out, PrintStream err) throws Exception {
        String fromRun = option(tokens, "--from-run=");
        if (fromRun == null || fromRun.isBlank()) {
            err.println("error: branch fork needs --from-run=<runId>");
            err.println("fix: store branch fork --from-run=<runId> [--name=<name>]");
            return 2;
        }
        RunPin run = readRun(project, fromRun);
        if (run == null) {
            err.println("error: unknown run " + fromRun);
            err.println("fix: store history show " + fromRun);
            return 2;
        }
        String requested = option(tokens, "--name=");
        String name = requested == null || requested.isBlank() ? forkName(fromRun) : requested;
        Binding binding = Binding.sandbox(name);
        String parent = parentOf(project, run.sandbox());
        if (Files.isRegularFile(StorePaths.sandboxDatabase(project, name))) {
            err.println("error: sandbox " + name + " already exists");
            err.println("fix: store branch discard --name=" + name);
            return 2;
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("owner", "branch");
        meta.put("ttl", "24h");
        meta.put("created_from_commit", run.commit());
        meta.put("created_from_dirty", "0");
        try {
            CowSandbox.create(project, name, parent, meta);
            try (SqlStore store = SqlStore.open(project, binding)) {
                Connection connection = store.session().connection();
                UniversalEntity.copyPin(connection, run.commit(), run.head(), false);
                DeltaLog.fork(connection, project, name, fromRun, run.head());
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                }
            }
        } finally {
            SqlConnections.closeBranch(project, binding.branch());
        }
        if (tokens.contains("--json")) {
            out.println("{\"name\":\"" + escape(name) + "\",\"fromRun\":\"" + escape(fromRun)
                    + "\",\"fromHead\":\"" + escape(run.head()) + "\",\"branch\":\"" + escape(name) + "\"}");
        } else {
            out.println("fork " + name);
            out.println("branch " + name);
            out.println("commit " + run.commit());
            out.println("head " + run.head());
        }
        return 0;
    }

    private static int discard(Path project, List<String> tokens, PrintStream out, PrintStream err) throws Exception {
        String name = option(tokens, "--name=");
        if (name == null || name.isBlank()) {
            err.println("error: branch discard needs --name=<name>");
            err.println("fix: store branch discard --name=<name>");
            return 2;
        }
        Binding binding = Binding.sandbox(name);
        if (!Files.isRegularFile(StorePaths.sandboxDatabase(project, name))) {
            err.println("error: unknown sandbox " + name);
            err.println("fix: store branch fork --from-run=<runId> --name=" + name);
            return 2;
        }
        int others;
        try (SqlStore store = SqlStore.open(project, binding)) {
            try (PreparedStatement statement = store.session().connection().prepareStatement(
                    "SELECT COUNT(*) FROM deltas WHERE kind <> 'fork'")) {
                try (ResultSet rows = statement.executeQuery()) {
                    others = rows.next() ? rows.getInt(1) : 0;
                }
            }
        } finally {
            SqlConnections.closeBranch(project, binding.branch());
        }
        if (others > 0 && !tokens.contains("--force")) {
            err.println("error: fork " + name + " is not empty");
            err.println("fix: store branch discard --name=" + name + " --force");
            return 2;
        }
        List<String> drop = new ArrayList<>();
        drop.add("sandbox");
        drop.add("drop");
        drop.add(name);
        drop.add("--force");
        return StoreSandbox.execute(project, drop, out, err);
    }

    /**
     * Lowercase run id, other characters become hyphens, at most 48 characters.
     * A leading digit is prefixed so the sandbox name matches {@link Binding}.
     */
    static String forkName(String runId) {
        String raw = runId == null ? "" : runId.toLowerCase(Locale.ROOT);
        StringBuilder built = new StringBuilder();
        for (int index = 0; index < raw.length() && built.length() < 47; index++) {
            char c = raw.charAt(index);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') built.append(c);
            else if (built.length() == 0 || built.charAt(built.length() - 1) != '-') built.append('-');
        }
        while (built.length() > 0 && built.charAt(built.length() - 1) == '-') built.setLength(built.length() - 1);
        String name = built.toString();
        if (name.isEmpty() || (name.charAt(0) >= '0' && name.charAt(0) <= '9')) name = "f" + name;
        if (name.length() > 48) name = name.substring(0, 48);
        while (name.endsWith("-")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty()) name = "fork";
        return name;
    }

    private static String parentOf(Path project, String sandbox) {
        if (sandbox == null || sandbox.isBlank() || "main".equals(sandbox)) return "main";
        if (!sandbox.matches("[a-z0-9][a-z0-9-]{0,47}")) return "main";
        if (!Files.isRegularFile(StorePaths.sandboxDatabase(project, sandbox))) return "main";
        return sandbox;
    }

    private static RunPin readRun(Path project, String runId) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT commit_sha, head_id, sandbox FROM runs WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                String commit = rows.getString(1);
                String head = rows.getString(2);
                String sandbox = rows.getString(3);
                return new RunPin(commit == null ? "" : commit, head == null ? "" : head, sandbox == null ? "" : sandbox);
            }
        }
    }

    private static String option(List<String> tokens, String prefix) {
        for (String token : tokens) {
            if (token != null && token.startsWith(prefix)) return token.substring(prefix.length());
        }
        return null;
    }

    private static int failed(PrintStream err, String message, String fix) {
        String text = message == null || message.isBlank() ? "branch failed" : message;
        if (text.startsWith("error:")) err.println(text);
        else err.println("error: " + text);
        if (!text.contains("fix:")) err.println("fix: " + fix);
        return 1;
    }

    private static String escape(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record RunPin(String commit, String head, String sandbox) {
    }
}
