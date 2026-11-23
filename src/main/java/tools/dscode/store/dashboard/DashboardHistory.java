package tools.dscode.store.dashboard;

import tools.dscode.store.StorePaths;
import tools.dscode.store.sql.DashboardAccess;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    /**
     * One element against stored history. The reply is lean JSON.
     * {@code --window} writes a separate HTML file and does not open a browser.
     */
    public static int xref(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        int limit = limit(tokens, stderr);
        if (limit < 0) return 2;
        String id = value(tokens, "--id");
        String selector = value(tokens, "--selector");
        String role = value(tokens, "--role");
        String name = value(tokens, "--name");
        List<String> hints = new ArrayList<>();
        if (!id.isEmpty()) hints.add(id);
        if (!selector.isEmpty()) hints.add(selector);
        if (!role.isEmpty()) hints.add(role);
        if (!name.isEmpty()) hints.add(name);
        if (hints.isEmpty()) {
            stderr.println("error: dashboard xref needs --id, --selector, or --role and --name");
            stderr.println("fix: dashboard xref --id=#save");
            return 2;
        }
        try {
            DashboardSnapshot snapshot = new SqliteDashboardSource(project).read(DashboardQuery.all());
            Evidence evidence = evidence(project);
            List<DashboardSnapshot.RunView> matched = new ArrayList<>();
            for (DashboardSnapshot.RunView run : snapshot.runs()) {
                if (matchesAll(run, snapshot, evidence, hints)) matched.add(run);
            }
            int total = matched.size();
            if (matched.size() > limit) matched = new ArrayList<>(matched.subList(0, limit));
            List<DashboardSnapshot.RunView> concurrent = concurrent(snapshot.runs(), matched);
            String summary = summary(hints, matched, concurrent);
            String overlay = cap(overlay(hints, matched, evidence, concurrent), 500);
            String chat = cap(chat(hints, matched, evidence), 240);
            String window = "";
            if (flag(tokens, "--window")) {
                Path file = windowFile(tokens, project);
                Files.createDirectories(file.getParent());
                Files.writeString(file, windowHtml(summary, matched, evidence, concurrent), StandardCharsets.UTF_8);
                window = StorePaths.display(project, file);
            }
            stdout.println(xrefJson(total, matched, evidence, concurrent, id, selector, role, name, summary, overlay, chat, window));
            return 0;
        } catch (Exception failure) {
            stderr.println("error: " + (failure.getMessage() == null ? failure : failure.getMessage()));
            stderr.println("fix: dashboard xref --project=<dir> --id=#save");
            return 1;
        }
    }

    private static String runsJson(int total, List<DashboardSnapshot.RunView> runs) {
        StringBuilder out = new StringBuilder();
        out.append("{\"ok\":true,\"op\":\"query\",\"count\":").append(runs.size())
                .append(",\"total\":").append(total).append(",\"runs\":[");
        for (int i = 0; i < runs.size(); i++) {
            if (i > 0) out.append(',');
            appendRun(out, runs.get(i));
        }
        return out.append("]}").toString();
    }

    private static void appendRun(StringBuilder out, DashboardSnapshot.RunView run) {
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

    private static String xrefJson(
            int total,
            List<DashboardSnapshot.RunView> runs,
            Evidence evidence,
            List<DashboardSnapshot.RunView> concurrent,
            String id,
            String selector,
            String role,
            String name,
            String summary,
            String overlay,
            String chat,
            String window
    ) {
        StringBuilder out = new StringBuilder();
        out.append("{\"ok\":true,\"op\":\"xref\",\"count\":").append(runs.size())
                .append(",\"total\":").append(total)
                .append(",\"element\":{\"id\":\"").append(esc(id))
                .append("\",\"selector\":\"").append(esc(selector))
                .append("\",\"role\":\"").append(esc(role))
                .append("\",\"name\":\"").append(esc(name))
                .append("\"},\"runs\":[");
        for (int i = 0; i < runs.size(); i++) {
            if (i > 0) out.append(',');
            appendRun(out, runs.get(i));
        }
        out.append("],\"scenarios\":").append(array(scenarios(runs)))
                .append(",\"logs\":").append(array(gather(runs, evidence.logs(), 8)))
                .append(",\"screenshots\":").append(array(gather(runs, evidence.shots(), 20)))
                .append(",\"concurrent\":[");
        for (int i = 0; i < concurrent.size(); i++) {
            if (i > 0) out.append(',');
            DashboardSnapshot.RunView run = concurrent.get(i);
            String label = run.scenario() == null || run.scenario().isBlank() ? run.id() : run.scenario();
            out.append("{\"name\":\"").append(esc(label))
                    .append("\",\"ref\":\"").append(esc(run.id()))
                    .append("\",\"status\":\"").append(esc(run.status()))
                    .append("\",\"startedAt\":\"").append(esc(run.startedAt()))
                    .append("\",\"finishedAt\":\"").append(esc(run.finishedAt()))
                    .append("\"}");
        }
        out.append("],\"summary\":\"").append(esc(summary))
                .append("\",\"overlay\":\"").append(esc(overlay))
                .append("\",\"chat\":\"").append(esc(chat)).append('"');
        if (!window.isEmpty()) out.append(",\"window\":\"").append(esc(window)).append('"');
        return out.append('}').toString();
    }

    private static boolean matchesAll(
            DashboardSnapshot.RunView run,
            DashboardSnapshot snapshot,
            Evidence evidence,
            List<String> hints
    ) {
        String blob = blob(run, snapshot, evidence).toLowerCase(Locale.ROOT);
        for (String hint : hints) {
            if (!blob.contains(hint.toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private static String blob(DashboardSnapshot.RunView run, DashboardSnapshot snapshot, Evidence evidence) {
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
        for (String line : evidence.lines().getOrDefault(run.id(), List.of())) append(blob, line);
        for (String line : evidence.logs().getOrDefault(run.id(), List.of())) append(blob, line);
        for (String line : evidence.shots().getOrDefault(run.id(), List.of())) append(blob, line);
        return blob.toString();
    }

    private static List<DashboardSnapshot.RunView> concurrent(
            List<DashboardSnapshot.RunView> all,
            List<DashboardSnapshot.RunView> matched
    ) {
        List<String> ids = matched.stream().map(DashboardSnapshot.RunView::id).toList();
        List<DashboardSnapshot.RunView> found = new ArrayList<>();
        for (DashboardSnapshot.RunView other : all) {
            if (ids.contains(other.id())) continue;
            for (DashboardSnapshot.RunView hit : matched) {
                if (overlaps(hit, other)) {
                    found.add(other);
                    break;
                }
            }
        }
        return found;
    }

    static boolean overlaps(DashboardSnapshot.RunView left, DashboardSnapshot.RunView right) {
        String aStart = left.startedAt() == null ? "" : left.startedAt();
        String bStart = right.startedAt() == null ? "" : right.startedAt();
        if (aStart.isBlank() || bStart.isBlank()) return false;
        boolean aPoint = left.finishedAt() == null || left.finishedAt().isBlank();
        boolean bPoint = right.finishedAt() == null || right.finishedAt().isBlank();
        if (aPoint && bPoint) return aStart.equals(bStart);
        if (aPoint) return between(aStart, bStart, right.finishedAt());
        if (bPoint) return between(bStart, aStart, left.finishedAt());
        return aStart.compareTo(right.finishedAt()) <= 0 && bStart.compareTo(left.finishedAt()) <= 0;
    }

    private static boolean between(String point, String start, String end) {
        return start.compareTo(point) <= 0 && point.compareTo(end) <= 0;
    }

    private static String summary(
            List<String> hints,
            List<DashboardSnapshot.RunView> matched,
            List<DashboardSnapshot.RunView> concurrent
    ) {
        String hint = String.join(" ", hints);
        if (matched.isEmpty()) return "No history mentions " + hint + ".";
        DashboardSnapshot.RunView first = matched.get(0);
        String label = first.scenario() == null || first.scenario().isBlank() ? first.id() : first.scenario();
        String text = count(matched.size(), "run") + " mentions " + hint + ": " + label + " " + first.status();
        if (!concurrent.isEmpty()) {
            text += ". " + count(concurrent.size(), "other run") + " overlap that time";
        }
        return text + ".";
    }

    private static String overlay(
            List<String> hints,
            List<DashboardSnapshot.RunView> matched,
            Evidence evidence,
            List<DashboardSnapshot.RunView> concurrent
    ) {
        String hint = String.join(" ", hints);
        if (matched.isEmpty()) return "History for " + hint + ": no related runs.";
        DashboardSnapshot.RunView first = matched.get(0);
        String label = first.scenario() == null || first.scenario().isBlank() ? first.id() : first.scenario();
        StringBuilder text = new StringBuilder();
        text.append("History for ").append(hint).append(": ")
                .append(label).append(" (").append(first.id()).append(") ").append(first.status()).append('.');
        if (!first.scenarios().isEmpty()) text.append(" Scenario ").append(first.scenarios().get(0)).append('.');
        List<String> shots = gather(List.of(first), evidence.shots(), 1);
        if (!shots.isEmpty()) text.append(" Screenshot ").append(shots.get(0)).append('.');
        if (!concurrent.isEmpty()) text.append(' ').append(count(concurrent.size(), "other run")).append(" overlap that time.");
        return text.toString();
    }

    private static String chat(List<String> hints, List<DashboardSnapshot.RunView> matched, Evidence evidence) {
        String hint = String.join(" ", hints);
        if (matched.isEmpty()) return "History for " + hint + ": no related runs.";
        DashboardSnapshot.RunView first = matched.get(0);
        StringBuilder text = new StringBuilder();
        text.append("History for ").append(hint).append(": ").append(first.id()).append(' ').append(first.status());
        if (!first.scenarios().isEmpty()) text.append(", ").append(first.scenarios().get(0));
        List<String> shots = gather(List.of(first), evidence.shots(), 1);
        if (!shots.isEmpty()) text.append(", ").append(shots.get(0));
        text.append('.');
        return text.toString();
    }

    private static Evidence evidence(Path project) throws SQLException {
        Map<String, List<String>> lines = new LinkedHashMap<>();
        Map<String, List<String>> logs = new LinkedHashMap<>();
        Map<String, List<String>> shots = new LinkedHashMap<>();
        try (DashboardAccess.Open open = DashboardAccess.open(project)) {
            if (open == null) return new Evidence(lines, logs, shots);
            String qualifier = open.runs();
            if (table(open, "scenario_executions") && table(open, "step_executions")) {
                readPairs(open, "SELECT se.run_id, st.step_text FROM " + qualifier
                        + "step_executions st JOIN " + qualifier
                        + "scenario_executions se ON se.exec_id = st.scenario_exec_id", lines);
                readPairs(open, "SELECT se.run_id, st.step_text FROM " + qualifier
                        + "step_executions st JOIN " + qualifier
                        + "scenario_executions se ON se.exec_id = st.scenario_exec_id "
                        + "WHERE lower(st.status) IN ('failed', 'failure')", logs);
            }
            if (table(open, "run_logs") && column(open, "run_logs", "message")) {
                readPairs(open, "SELECT run_id, message FROM " + qualifier + "run_logs", logs);
            }
            if (table(open, "artifacts")) {
                boolean kind = column(open, "artifacts", "kind");
                String sql = kind
                        ? "SELECT run_id, path, kind FROM " + qualifier + "artifacts ORDER BY path"
                        : "SELECT run_id, path, '' FROM " + qualifier + "artifacts ORDER BY path";
                try (PreparedStatement statement = open.connection().prepareStatement(sql);
                     ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String run = rows.getString(1);
                        String path = rows.getString(2);
                        if (run == null || run.isBlank() || path == null || path.isBlank()) continue;
                        if (!screenshot(path, rows.getString(3))) continue;
                        shots.computeIfAbsent(run, key -> new ArrayList<>()).add(path);
                    }
                }
            }
        }
        return new Evidence(lines, logs, shots);
    }

    static boolean screenshot(String path, String kind) {
        String file = path == null ? "" : path.toLowerCase(Locale.ROOT);
        String label = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        if (file.endsWith(".png") || file.endsWith(".jpg") || file.endsWith(".jpeg") || file.endsWith(".webp")) {
            return true;
        }
        if (file.contains("shot")) return true;
        return label.contains("shot") || label.contains("image") || "png".equals(label);
    }

    private static List<String> scenarios(List<DashboardSnapshot.RunView> runs) {
        List<String> found = new ArrayList<>();
        for (DashboardSnapshot.RunView run : runs) {
            for (String scenario : run.scenarios()) {
                if (scenario == null || scenario.isBlank() || found.contains(scenario)) continue;
                found.add(scenario);
                if (found.size() == 20) return found;
            }
        }
        return found;
    }

    private static List<String> gather(
            List<DashboardSnapshot.RunView> runs,
            Map<String, List<String>> values,
            int cap
    ) {
        List<String> found = new ArrayList<>();
        if (cap == 0) return found;
        for (DashboardSnapshot.RunView run : runs) {
            for (String value : values.getOrDefault(run.id(), List.of())) {
                String text = excerpt(value);
                if (text.isEmpty() || found.contains(text)) continue;
                found.add(text);
                if (found.size() == cap) return found;
            }
        }
        return found;
    }

    private static String excerpt(String value) {
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= 160 ? text : text.substring(0, 160);
    }

    private static String cap(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String count(int n, String noun) {
        if ("other run".equals(noun)) return n + " other " + (n == 1 ? "run" : "runs");
        return n + " " + (n == 1 ? noun : noun + "s");
    }

    private static Path windowFile(List<String> tokens, Path project) {
        String chosen = value(tokens, "--out");
        if (!chosen.isEmpty()) return Path.of(chosen).toAbsolutePath().normalize();
        return project.resolve(".pickleball").resolve("dashboard").resolve("xref.html");
    }

    private static boolean flag(List<String> tokens, String name) {
        return tokens.contains(name);
    }

    private static String windowHtml(
            String summary,
            List<DashboardSnapshot.RunView> runs,
            Evidence evidence,
            List<DashboardSnapshot.RunView> concurrent
    ) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<title>History</title>\n<style>\n");
        html.append("body{font:16px/1.45 sans-serif;margin:24px;max-width:720px;color:#1b1f24}\n");
        html.append("h1{font-size:20px} section{margin:16px 0} li{margin:4px 0}\n");
        html.append("</style>\n</head>\n<body>\n<h1>History</h1>\n<p>").append(html(summary)).append("</p>\n");
        section(html, "Runs", runLines(runs));
        section(html, "Scenarios", scenarios(runs));
        section(html, "Logs", gather(runs, evidence.logs(), 8));
        section(html, "Screenshots", gather(runs, evidence.shots(), 20));
        section(html, "Other runs", concurrent.stream().map(DashboardSnapshot.RunView::id).toList());
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private static List<String> runLines(List<DashboardSnapshot.RunView> runs) {
        List<String> lines = new ArrayList<>();
        for (DashboardSnapshot.RunView run : runs) {
            String label = run.scenario() == null || run.scenario().isBlank() ? run.id() : run.scenario();
            lines.add(label + " (" + run.id() + ") " + run.status());
        }
        return lines;
    }

    private static void section(StringBuilder html, String title, List<String> rows) {
        html.append("<section><h2>").append(html(title)).append("</h2><ul>");
        if (rows.isEmpty()) html.append("<li>None</li>");
        for (String row : rows) html.append("<li>").append(html(row)).append("</li>");
        html.append("</ul></section>\n");
    }

    private static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record Evidence(
            Map<String, List<String>> lines,
            Map<String, List<String>> logs,
            Map<String, List<String>> shots
    ) {
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
