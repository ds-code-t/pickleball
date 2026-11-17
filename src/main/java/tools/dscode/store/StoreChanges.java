package tools.dscode.store;

import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlStore;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** {@code pickleball store changes} and change-log retention. */
public final class StoreChanges {
    public static final int DEFAULT_DAYS = 14;

    private StoreChanges() {
    }

    public record Row(long seq, String at, String table, String key, String op) {
    }

    public static int execute(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        boolean json = tokens.contains("--json");
        try {
            String sinceRaw = option(tokens, "--since=");
            String table = option(tokens, "--table=");
            String limitRaw = option(tokens, "--limit=");
            long since = sinceRaw == null || sinceRaw.isBlank() ? -1L : Long.parseLong(sinceRaw.trim());
            int limit = limitRaw == null || limitRaw.isBlank() ? 100 : Integer.parseInt(limitRaw.trim());
            if (limit < 1) throw new IllegalArgumentException("changes limit must be at least 1");
            Binding binding = StoreScope.current();
            List<Row> rows;
            if (!StorePaths.ready(project) && binding.primary()) {
                rows = List.of();
            } else {
                try (StoreDatabase database = StoreDatabase.open(project, binding)) {
                    rows = list(database.connection(), table, since, limit);
                }
            }
            String entity = table == null || table.isBlank() ? "*" : table;
            StoreLog.log(project, "changes", entity, StoreScope.label(binding), "count=" + rows.size());
            if (json) out.println(json(binding, rows));
            else out.print(text(rows));
            return 0;
        } catch (NumberFormatException failure) {
            err.println("error: changes expected an integer");
            err.println("fix: store changes --since=<seq> --limit=<n>");
            return 1;
        } catch (RuntimeException | SQLException failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            err.println(message.startsWith("error:") ? message : "error: " + message);
            err.println("fix: store changes --json");
            return 1;
        }
    }

    public static int days() {
        String raw = StoreLog.setting("pkb_store_changes_days", "PKB_STORE_CHANGES_DAYS");
        if (raw == null || raw.isBlank()) return DEFAULT_DAYS;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(parsed, 0);
        } catch (NumberFormatException failure) {
            return DEFAULT_DAYS;
        }
    }

    public static int trim(Connection connection, int days) throws SQLException {
        String cutoff = Instant.now().minus(java.time.Duration.ofDays(Math.max(days, 0)))
                .truncatedTo(ChronoUnit.SECONDS).toString().replace("Z", "");
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM _pkb_changes WHERE substr(at, 1, 19) < ?")) {
            statement.setString(1, cutoff);
            return statement.executeUpdate();
        }
    }

    /** Trims {@code main} and every sandbox file. A busy sandbox is skipped. */
    public static int trimAll(Path project) {
        int removed = 0;
        int keep = days();
        if (StorePaths.ready(project)) {
            try (StoreDatabase database = StoreDatabase.open(project, Binding.main())) {
                removed += trim(database.connection(), keep);
            } catch (SQLException ignored) {
                // A locked main is reported by the command that already failed to open it.
            }
        }
        Path root = StorePaths.root(project).resolve("sandboxes");
        if (!Files.isDirectory(root)) return removed;
        List<Path> homes;
        try (var stream = Files.list(root)) {
            homes = stream.filter(dir -> Files.isRegularFile(dir.resolve("source.db"))).toList();
        } catch (java.io.IOException failure) {
            return removed;
        }
        for (Path home : homes) {
            String name = home.getFileName().toString();
            if (!name.matches("[a-z0-9][a-z0-9-]{0,47}")) continue;
            try (SqlStore store = SqlStore.open(project, Binding.sandbox(name))) {
                removed += trim(store.session().connection(), keep);
            } catch (Exception ignored) {
                // An in-use sandbox keeps its change log until the next prune.
            }
        }
        if (removed > 0) StoreLog.log(project, "changes", "*", "main", "prune removed=" + removed);
        return removed;
    }

    private static List<Row> list(Connection connection, String table, long since, int limit) throws SQLException {
        String sql = """
                SELECT seq, at, tbl, pk, op FROM _pkb_changes
                WHERE (? = '' OR tbl = ?) AND (? < 0 OR seq > ?)
                ORDER BY seq
                LIMIT ?
                """;
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            String filter = table == null ? "" : table.trim();
            statement.setString(1, filter);
            statement.setString(2, filter);
            statement.setLong(3, since);
            statement.setLong(4, since);
            statement.setInt(5, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new Row(
                            result.getLong(1),
                            result.getString(2) == null ? "" : result.getString(2),
                            result.getString(3) == null ? "" : result.getString(3),
                            result.getString(4) == null ? "" : result.getString(4),
                            result.getString(5) == null ? "" : result.getString(5)));
                }
            }
        }
        return rows;
    }

    private static String json(Binding binding, List<Row> rows) {
        StringBuilder body = new StringBuilder();
        body.append("{\"command\":\"changes\",\"sandbox\":\"")
                .append(escape(StoreScope.label(binding)))
                .append("\",\"count\":").append(rows.size()).append(",\"rows\":[");
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) body.append(',');
            Row row = rows.get(index);
            body.append("{\"seq\":").append(row.seq())
                    .append(",\"at\":\"").append(escape(row.at()))
                    .append("\",\"table\":\"").append(escape(row.table()))
                    .append("\",\"key\":\"").append(escape(row.key()))
                    .append("\",\"op\":\"").append(escape(row.op()))
                    .append("\"}");
        }
        body.append("]}");
        return body.toString();
    }

    private static String text(List<Row> rows) {
        StringBuilder body = new StringBuilder();
        body.append("changes ").append(rows.size()).append('\n');
        for (Row row : rows) {
            body.append(row.seq()).append(' ').append(row.at()).append(' ')
                    .append(row.table()).append(' ').append(row.key()).append(' ').append(row.op()).append('\n');
        }
        return body.toString();
    }

    private static String option(List<String> tokens, String prefix) {
        for (String token : tokens) {
            if (token != null && token.startsWith(prefix)) return token.substring(prefix.length());
        }
        return null;
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
