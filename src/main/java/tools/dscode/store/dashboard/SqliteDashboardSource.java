package tools.dscode.store.dashboard;

import tools.dscode.store.sql.DashboardAccess;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the embedded store files for one project. A later backend implements
 * {@link DashboardSource} the same way. This class does not open a socket.
 */
public final class SqliteDashboardSource implements DashboardSource {
    private static final int MAX_RUNS = 2000;

    private final Path project;

    public SqliteDashboardSource(Path project) {
        this.project = project;
    }

    @Override
    public DashboardSnapshot read(DashboardQuery query) throws SQLException {
        DashboardQuery filter = query == null ? DashboardQuery.all() : query;
        try (DashboardAccess.Open open = DashboardAccess.open(project)) {
            if (open == null) return empty();
            String repo = open.entities() ? githubRepo(open.connection()) : "";
            List<DashboardSnapshot.RunView> runs = runs(open, filter, repo);
            List<String> ids = runs.stream().map(DashboardSnapshot.RunView::id).toList();
            List<DashboardSnapshot.Marker> markers = new ArrayList<>();
            Map<String, Integer> entities = new LinkedHashMap<>();
            Map<String, Integer> cases = new LinkedHashMap<>();
            Map<String, Integer> errors = new LinkedHashMap<>();
            Map<String, Integer> caseTypes = new LinkedHashMap<>();
            for (DashboardSnapshot.RunView run : runs) {
                markers.add(new DashboardSnapshot.Marker(run.startedAt(), "run", run.id(), run.status(), run.id()));
                for (String scenario : run.scenarios()) {
                    markers.add(new DashboardSnapshot.Marker(run.startedAt(), "scenario", scenario, scenario, run.id()));
                }
            }
            if (!ids.isEmpty()) {
                usages(open, ids, markers, entities, cases, caseTypes);
                errors(open, ids, markers, errors);
            }
            return new DashboardSnapshot(
                    Instant.now().toString(),
                    runs,
                    markers,
                    counts(entities),
                    counts(cases),
                    counts(errors),
                    counts(caseTypes)
            );
        }
    }

    private static DashboardSnapshot empty() {
        return new DashboardSnapshot(Instant.now().toString(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static List<DashboardSnapshot.RunView> runs(DashboardAccess.Open open, DashboardQuery filter, String repo) throws SQLException {
        String r = open.runs();
        StringBuilder sql = new StringBuilder();
        List<String> args = new ArrayList<>();
        sql.append("SELECT run_id, started_at, finished_at, status, scenario, passed, failed, note, ");
        sql.append(column(open.connection(), r, "runs", "git_commit")).append(", ");
        sql.append(column(open.connection(), r, "runs", "feature_path"));
        sql.append(" FROM ").append(r).append("runs WHERE 1 = 1");
        if (!filter.from().isEmpty()) {
            sql.append(" AND started_at >= ?");
            args.add(filter.from());
        }
        if (!filter.to().isEmpty()) {
            sql.append(" AND started_at <= ?");
            args.add(filter.to());
        }
        if (!filter.status().isEmpty()) {
            sql.append(" AND lower(status) = lower(?)");
            args.add(filter.status());
        }
        if (!filter.runId().isEmpty()) {
            sql.append(" AND run_id = ?");
            args.add(filter.runId());
        }
        if (!filter.feature().isEmpty()) {
            sql.append(" AND (instr(lower(feature_path), lower(?)) > 0 OR run_id IN (SELECT se.run_id FROM ")
                    .append(r).append("scenario_executions se WHERE instr(lower(se.scenario_key), lower(?)) > 0))");
            args.add(filter.feature());
            args.add(filter.feature());
        }
        if (!filter.text().isEmpty()) {
            sql.append(" AND (instr(lower(note), lower(?)) > 0 OR instr(lower(scenario), lower(?)) > 0 OR run_id IN (")
                    .append("SELECT se.run_id FROM ").append(r).append("scenario_executions se JOIN ")
                    .append(r).append("step_executions st ON st.scenario_exec_id = se.exec_id ")
                    .append("WHERE instr(lower(st.step_text), lower(?)) > 0))");
            args.add(filter.text());
            args.add(filter.text());
            args.add(filter.text());
        }
        if (!filter.entity().isEmpty()) {
            sql.append(" AND run_id IN (SELECT se.run_id FROM ").append(r).append("scenario_executions se JOIN ")
                    .append(r).append("step_executions st ON st.scenario_exec_id = se.exec_id JOIN ")
                    .append(r).append("entity_usages u ON u.step_exec_id = st.step_exec_id WHERE ");
            entityMatch(sql, args, filter.entity(), "u");
            sql.append(" UNION SELECT rl.run_id FROM ").append(r).append("run_logs rl JOIN ")
                    .append(r).append("log_entity_links lk ON lk.log_id = rl.id WHERE ");
            entityMatch(sql, args, filter.entity(), "lk");
            sql.append(")");
        }
        sql.append(" ORDER BY started_at, run_id LIMIT ").append(MAX_RUNS);
        List<DashboardSnapshot.RunView> runs = new ArrayList<>();
        try (PreparedStatement statement = open.connection().prepareStatement(sql.toString())) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    String feature = rows.getString(10) == null ? "" : rows.getString(10);
                    List<String> scenarios = columnList(open, "SELECT scenario_key FROM " + r
                            + "scenario_executions WHERE run_id = ? ORDER BY started_at", id);
                    List<String> features = features(feature, scenarios);
                    List<String> data = dataFiles(open, id);
                    List<String> artifacts = columnList(open, "SELECT path FROM " + r
                            + "artifacts WHERE run_id = ? ORDER BY path", id);
                    String commit = rows.getString(9) == null ? "" : rows.getString(9);
                    runs.add(new DashboardSnapshot.RunView(
                            id,
                            text(rows.getString(2)),
                            text(rows.getString(3)),
                            text(rows.getString(4)),
                            text(rows.getString(5)),
                            rows.getInt(6),
                            rows.getInt(7),
                            text(rows.getString(8)),
                            commit,
                            feature,
                            commitUrl(repo, commit),
                            features,
                            data,
                            artifacts,
                            scenarios
                    ));
                }
            }
        }
        return runs;
    }

    private static void usages(
            DashboardAccess.Open open,
            List<String> runIds,
            List<DashboardSnapshot.Marker> markers,
            Map<String, Integer> entities,
            Map<String, Integer> cases,
            Map<String, Integer> caseTypes
    ) throws SQLException {
        String r = open.runs();
        String marks = marks(runIds.size());
        String sql = "SELECT u.logged_at, u.entity_type, u.entity_id, se.run_id FROM " + r
                + "entity_usages u JOIN " + r + "step_executions st ON st.step_exec_id = u.step_exec_id JOIN "
                + r + "scenario_executions se ON se.exec_id = st.scenario_exec_id WHERE se.run_id IN (" + marks + ") "
                + "UNION ALL SELECT rl.logged_at, lk.entity_type, lk.entity_id, rl.run_id FROM " + r
                + "log_entity_links lk JOIN " + r + "run_logs rl ON rl.id = lk.log_id WHERE rl.run_id IN (" + marks + ")";
        List<String> args = new ArrayList<>(runIds);
        args.addAll(runIds);
        Map<String, String> types = caseTypes(open);
        try (PreparedStatement statement = open.connection().prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String type = text(rows.getString(2));
                    String id = text(rows.getString(3));
                    String key = type + "#" + id;
                    String at = text(rows.getString(1));
                    String runId = text(rows.getString(4));
                    if (isCase(type)) {
                        bump(cases, key);
                        String kind = types.getOrDefault(key.toLowerCase(Locale.ROOT), "unspecified");
                        bump(caseTypes, kind);
                        markers.add(new DashboardSnapshot.Marker(at, "case", key, kind, runId));
                    } else {
                        bump(entities, key);
                        markers.add(new DashboardSnapshot.Marker(at, "entity", key, type, runId));
                    }
                }
            }
        }
    }

    private static void errors(
            DashboardAccess.Open open,
            List<String> runIds,
            List<DashboardSnapshot.Marker> markers,
            Map<String, Integer> errors
    ) throws SQLException {
        String r = open.runs();
        String sql = "SELECT st.started_at, st.step_text, se.run_id FROM " + r
                + "step_executions st JOIN " + r + "scenario_executions se ON se.exec_id = st.scenario_exec_id "
                + "WHERE lower(st.status) IN ('failed', 'failure') AND se.run_id IN (" + marks(runIds.size()) + ")";
        try (PreparedStatement statement = open.connection().prepareStatement(sql)) {
            bind(statement, runIds);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String step = text(rows.getString(2));
                    if (step.isEmpty()) continue;
                    bump(errors, step);
                    markers.add(new DashboardSnapshot.Marker(text(rows.getString(1)), "error", step, step, text(rows.getString(3))));
                }
            }
        }
    }

    private static String marks(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Map<String, String> caseTypes(DashboardAccess.Open open) throws SQLException {
        Map<String, String> types = new LinkedHashMap<>();
        if (!open.entities() || !table(open.connection(), "", "entities")) return types;
        try (PreparedStatement statement = open.connection().prepareStatement(
                "SELECT entity_type, entity_id, payload_json, source_file FROM entities");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                if (!isCase(rows.getString(1))) continue;
                String key = (text(rows.getString(1)) + "#" + text(rows.getString(2))).toLowerCase(Locale.ROOT);
                types.put(key, caseType(rows.getString(3)));
            }
        }
        return types;
    }

    static String caseType(String payload) {
        if (payload == null) return "unspecified";
        for (String field : List.of("\"caseType\"", "\"type\"")) {
            int at = payload.indexOf(field);
            if (at < 0) continue;
            int colon = payload.indexOf(':', at + field.length());
            if (colon < 0) continue;
            int quote = payload.indexOf('"', colon + 1);
            if (quote < 0) continue;
            int end = payload.indexOf('"', quote + 1);
            if (end > quote + 1) return payload.substring(quote + 1, end);
        }
        return "unspecified";
    }

    private static List<String> dataFiles(DashboardAccess.Open open, String runId) throws SQLException {
        if (!open.entities() || !table(open.connection(), "", "entities")) return List.of();
        String r = open.runs();
        return columnList(open, "SELECT DISTINCT e.source_file FROM entities e JOIN "
                + r + "entity_usages u ON u.entity_type = e.entity_type AND u.entity_id = e.entity_id JOIN "
                + r + "step_executions st ON st.step_exec_id = u.step_exec_id JOIN "
                + r + "scenario_executions se ON se.exec_id = st.scenario_exec_id "
                + "WHERE se.run_id = ? AND e.source_file IS NOT NULL AND e.source_file <> '' "
                + "ORDER BY e.source_file", runId);
    }

    private static List<String> features(String stored, List<String> scenarios) {
        List<String> found = new ArrayList<>();
        for (String line : stored.split("\n")) addFeature(found, line);
        for (String scenario : scenarios) addFeature(found, scenario);
        return List.copyOf(found);
    }

    private static void addFeature(List<String> found, String raw) {
        if (raw == null) return;
        String path = raw.trim().replace('\\', '/');
        int bar = path.indexOf('|');
        if (bar >= 0) path = path.substring(0, bar).trim();
        if (path.isEmpty() || found.contains(path)) return;
        if (!path.contains("/") && !path.endsWith(".feature")) return;
        found.add(path);
    }

    private static String commitUrl(String repo, String commit) {
        if (repo == null || commit == null || repo.isBlank() || commit.isBlank()) return "";
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) return "";
        if (!commit.matches("[0-9a-fA-F]{7,64}")) return "";
        return "https://github.com/" + repo + "/commit/" + commit;
    }

    private static String githubRepo(Connection connection) throws SQLException {
        if (!table(connection, "", "provenance")) return "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT github_repo FROM provenance WHERE id = 1");
             ResultSet rows = statement.executeQuery()) {
            return rows.next() && rows.getString(1) != null ? rows.getString(1) : "";
        }
    }

    private static String column(Connection connection, String qualifier, String table, String name) throws SQLException {
        return tableHas(connection, qualifier, table, name) ? name : "''";
    }

    private static boolean tableHas(Connection connection, String qualifier, String table, String column) throws SQLException {
        String pragma = "PRAGMA " + qualifier + "table_info(" + table + ")";
        try (PreparedStatement statement = connection.prepareStatement(pragma);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                if (column.equalsIgnoreCase(rows.getString("name"))) return true;
            }
        }
        return false;
    }

    private static boolean table(Connection connection, String qualifier, String name) throws SQLException {
        String sql = "SELECT 1 FROM " + qualifier + "sqlite_master WHERE type = 'table' AND name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static List<String> columnList(DashboardAccess.Open open, String sql, String arg) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = open.connection().prepareStatement(sql)) {
            statement.setString(1, arg);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String value = rows.getString(1);
                    if (value != null && !value.isBlank()) values.add(value);
                }
            }
        }
        return values;
    }

    private static void entityMatch(StringBuilder sql, List<String> args, String entity, String alias) {
        int hash = entity.indexOf('#');
        if (hash > 0) {
            sql.append("lower(").append(alias).append(".entity_type) = lower(?) AND ")
                    .append(alias).append(".entity_id = ?");
            args.add(entity.substring(0, hash));
            args.add(entity.substring(hash + 1));
        } else {
            sql.append("(lower(").append(alias).append(".entity_type) = lower(?) OR ")
                    .append(alias).append(".entity_id = ?)");
            args.add(entity);
            args.add(entity);
        }
    }

    private static void bind(PreparedStatement statement, List<String> args) throws SQLException {
        for (int index = 0; index < args.size(); index++) statement.setString(index + 1, args.get(index));
    }

    private static boolean isCase(String type) {
        return type != null && "case".equals(type.trim().toLowerCase(Locale.ROOT));
    }

    private static void bump(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static List<DashboardSnapshot.Count> counts(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted((left, right) -> {
                    int byCount = Integer.compare(right.getValue(), left.getValue());
                    return byCount != 0 ? byCount : left.getKey().compareTo(right.getKey());
                })
                .map(entry -> new DashboardSnapshot.Count(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
