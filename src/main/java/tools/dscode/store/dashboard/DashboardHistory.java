package tools.dscode.store.dashboard;

import tools.dscode.store.sql.DashboardAccess;

import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lean, read-only answers over the same store the HTML page reads.
 * Nothing here writes a database or starts a server.
 */
public final class DashboardHistory {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private DashboardHistory() {
    }

    public static int query(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        int limit = limit(tokens, stderr);
        if (limit < 0) return 2;
        DashboardQuery filter = new DashboardQuery(
                value(tokens, "--from"),
                value(tokens, "--to"),
                value(tokens, "--status"),
                value(tokens, "--feature"),
                value(tokens, "--run"),
                value(tokens, "--entity"),
                value(tokens, "--text")
        );
        String element = value(tokens, "--element");
        try {
            DashboardSnapshot snapshot = new SqliteDashboardSource(project).read(filter);
            Map<String, List<String>> lines = element.isEmpty() ? Map.of() : lines(project);
            List<DashboardSnapshot.RunView> matched = new ArrayList<>();
            for (DashboardSnapshot.RunView run : snapshot.runs()) {
                if (element.isEmpty() || matches(run, snapshot, lines, element)) matched.add(run);
            }
            int total = matched.size();
            if (matched.size() > limit) matched = matched.subList(0, limit);
            stdout.println(runsJson(total, matched));
            return 0;
        } catch (Exception failure) {
            stderr.println("error: " + (failure.getMessage() == null ? failure : failure.getMessage()));
            stderr.println("fix: dashboard query --project=<dir>");
            return 1;
        }
    }

    private static String runsJson(int total, List<DashboardSnapshot.RunView> runs) {
        StringBuilder out = new StringBuilder();
        out.append("{\"ok\":true,\"op\":\"query\",\"count\":").append(runs.size())
                .append(",\"total\":").append(total).append(",\"runs\":[");
        for (int i = 0; i < runs.size(); i++) {
            if (i > 0) out.append(',');
            DashboardSnapshot.RunView run = runs.get(i);
            String name = run.scenario() == null || run.scenario().isBlank() ? run.id() : run.scenario();
            out.append("{\"name\":\"").append(esc(name))
                    .append("\",\"ref\":\"").append(esc(run.id()))
                    .append("\",\"status\":\"").append(esc(run.status()))
                    .append("\",\"startedAt\":\"").append(esc(run.startedAt()))
                    .append("\",\"finishedAt\":\"").append(esc(run.finishedAt()))
                    .append("\",\"feature\":\"").append(esc(feature(run)))
                    .append("\",\"scenarios\":").append(array(run.scenarios()))
                    .append(",\"artifacts\":").append(array(run.artifacts()))
                    .append(",\"data\":").append(array(run.dataFiles()))
                    .append(",\"summary\":\"").append(esc(summary(run)))
                    .append("\"}");
        }
        return out.append("]}").toString();
    }

    private static String feature(DashboardSnapshot.RunView run) {
        if (run.features() != null && !run.features().isEmpty()) return run.features().get(0);
        String stored = run.featurePath() == null ? "" : run.featurePath();
        int newline = stored.indexOf('\n');
        return newline < 0 ? stored : stored.substring(0, newline);
    }

    private static String summary(DashboardSnapshot.RunView run) {
        String text = (run.status() == null ? "" : run.status()) + " " + feature(run)
                + " failed " + run.failed();
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= 160 ? text : text.substring(0, 160);
    }

    private static boolean matches(
            DashboardSnapshot.RunView run,
            DashboardSnapshot snapshot,
            Map<String, List<String>> lines,
            String hint
    ) {
        String needle = hint.toLowerCase(Locale.ROOT);
        StringBuilder blob = new StringBuilder();
        append(blob, run.id());
        append(blob, run.scenario());
        append(blob, run.note());
        append(blob, run.featurePath());
        if (run.features() != null) run.features().forEach(value -> append(blob, value));
        if (run.dataFiles() != null) run.dataFiles().forEach(value -> append(blob, value));
        if (run.artifacts() != null) run.artifacts().forEach(value -> append(blob, value));
        if (run.scenarios() != null) run.scenarios().forEach(value -> append(blob, value));
        for (DashboardSnapshot.Marker marker : snapshot.markers()) {
            if (!run.id().equals(marker.runId())) continue;
            append(blob, marker.id());
            append(blob, marker.label());
        }
        for (String line : lines.getOrDefault(run.id(), List.of())) append(blob, line);
        return blob.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static void append(StringBuilder blob, String value) {
        if (value != null && !value.isBlank()) blob.append(value).append('\n');
    }

    private static Map<String, List<String>> lines(Path project) throws SQLException {
        Map<String, List<String>> found = new LinkedHashMap<>();
        try (DashboardAccess.Open open = DashboardAccess.open(project)) {
            if (open == null) return found;
            String qualifier = open.runs();
            if (table(open, "scenario_executions") && table(open, "step_executions")) {
                readPairs(open, "SELECT se.run_id, st.step_text FROM " + qualifier
                        + "step_executions st JOIN " + qualifier
                        + "scenario_executions se ON se.exec_id = st.scenario_exec_id", found);
            }
            if (table(open, "run_logs") && column(open, "run_logs", "message")) {
                readPairs(open, "SELECT run_id, message FROM " + qualifier + "run_logs", found);
            }
        }
        return found;
    }

    private static void readPairs(DashboardAccess.Open open, String sql, Map<String, List<String>> found) throws SQLException {
        try (PreparedStatement statement = open.connection().prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String run = rows.getString(1);
                String text = rows.getString(2);
                if (run == null || run.isBlank() || text == null || text.isBlank()) continue;
                found.computeIfAbsent(run, key -> new ArrayList<>()).add(text);
            }
        }
    }

    private static boolean table(DashboardAccess.Open open, String name) throws SQLException {
        String sql = "SELECT 1 FROM " + open.runs() + "sqlite_master WHERE type = 'table' AND name = ?";
        try (PreparedStatement statement = open.connection().prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static boolean column(DashboardAccess.Open open, String table, String name) throws SQLException {
        String pragma = "PRAGMA " + open.runs() + "table_info(" + table + ")";
        try (PreparedStatement statement = open.connection().prepareStatement(pragma);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                if (name.equalsIgnoreCase(rows.getString("name"))) return true;
            }
        }
        return false;
    }

    static int limit(List<String> tokens, PrintStream err) {
        String raw = value(tokens, "--limit");
        if (raw.isEmpty()) return DEFAULT_LIMIT;
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 0) {
                err.println("error: --limit must be a whole number");
                err.println("fix: dashboard query --limit=20");
                return -1;
            }
            return Math.min(parsed, MAX_LIMIT);
        } catch (NumberFormatException failure) {
            err.println("error: --limit must be a whole number");
            err.println("fix: dashboard query --limit=20");
            return -1;
        }
    }

    static String value(List<String> tokens, String name) {
        String prefix = name + "=";
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.startsWith(prefix)) return token.substring(prefix.length()).trim();
            if (name.equals(token) && index + 1 < tokens.size()) return tokens.get(index + 1).trim();
        }
        return "";
    }

    private static String array(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) out.append(',');
                out.append('"').append(esc(values.get(i))).append('"');
            }
        }
        return out.append(']').toString();
    }

    private static String esc(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\r' -> out.append("\\r");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                default -> {
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
