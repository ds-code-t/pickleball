package tools.dscode.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read-only query and timeline over linked tracking rows.
 * A missing database is an empty result and is not created.
 * Filters and the page limit run in SQL. The page is not the whole table.
 */
public final class StoreTrack {
    private StoreTrack() {
    }

    public record Filter(
            String entity,
            String field,
            String stepDef,
            String scenario,
            String status,
            String from,
            String to,
            String cursor,
            int limit,
            int offset,
            String before,
            String tag,
            String feature,
            String run,
            String text,
            boolean latest
    ) {
        public Filter {
            if (limit < 0) throw new IllegalArgumentException("limit must be zero or greater");
            if (offset < 0) throw new IllegalArgumentException("offset must be zero or greater");
        }

        /** Older callers that do not pass the fix-back filters. */
        public Filter(
                String entity, String field, String stepDef, String scenario, String status,
                String from, String to, String cursor, int limit, int offset
        ) {
            this(entity, field, stepDef, scenario, status, from, to, cursor, limit, offset,
                    null, null, null, null, null, false);
        }
    }

    public static String query(Path project, Filter filter, boolean json) throws SQLException {
        Page page = select(project, filter, false);
        StoreLog.log(project, "query", "query", "rows=" + page.events().size());
        return json ? json("query", page, filter, false) : text(page.events());
    }

    public static String timeline(Path project, Filter filter, boolean json) throws SQLException {
        Page page = select(project, filter, true);
        StoreLog.log(project, "track", "timeline", "events=" + page.events().size());
        return json ? json("timeline", page, filter, true) : text(page.events());
    }

    private record Page(List<Event> events, int total, int offset) {
    }

    private static Page select(Path project, Filter filter, boolean timeline) throws SQLException {
        instant(filter.from(), "--from");
        instant(filter.to(), "--to");
        Cursor after = cursor(filter.cursor(), "--cursor");
        Cursor before = cursor(filter.before(), "--before");
        if (project == null || !StorePaths.ready(project)) {
            return new Page(List.of(), 0, filter.offset());
        }
        try (StoreDatabase database = StoreDatabase.open(project)) {
            Connection connection = database.connection();
            int total = count(connection, filter, timeline, null, null);
            int offset = filter.offset();
            if (filter.latest() && before == null && (filter.cursor() == null || filter.cursor().isBlank()) && offset == 0) {
                offset = Math.max(0, total - filter.limit());
            }
            List<Event> events = before == null
                    ? read(connection, filter, timeline, after, null, filter.limit(), offset, false)
                    : read(connection, filter, timeline, null, before, filter.limit(), 0, true);
            return new Page(events, total, before == null ? offset : filter.offset());
        }
    }

    private static int count(Connection connection, Filter filter, boolean timeline, Cursor after, Cursor before) throws SQLException {
        Sql sql = statement(filter, timeline, after, before, false, 0, 0);
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM (" + sql.body + ") counted")) {
            sql.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private static List<Event> read(
            Connection connection, Filter filter, boolean timeline, Cursor after, Cursor before,
            int limit, int offset, boolean rewind
    ) throws SQLException {
        Sql sql = statement(filter, timeline, after, before, true, limit, offset);
        String body = sql.body;
        if (rewind) {
            body = "SELECT * FROM (" + body + ") page ORDER BY at ASC, kind_rank ASC, id ASC";
        }
        try (PreparedStatement statement = connection.prepareStatement(body)) {
            sql.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                List<Event> events = new ArrayList<>();
                while (rows.next()) events.add(event(rows));
                return events;
            }
        }
    }

    private record Sql(String body, List<Object> values) {
        private void bind(PreparedStatement statement) throws SQLException {
            int index = 1;
            for (Object value : values) {
                if (value instanceof Integer number) statement.setInt(index++, number);
                else statement.setString(index++, value == null ? "" : value.toString());
            }
        }
    }

    private static Sql statement(
            Filter filter, boolean timeline, Cursor after, Cursor before, boolean page, int limit, int offset
    ) {
        StringBuilder sql = new StringBuilder();
        List<Object> values = new ArrayList<>();
        sql.append("SELECT at, kind, id, run_id, status, scenario_key, step_exec_id, step_text, definition_id, ");
        sql.append("entity_type, entity_id, field, operation, value_ref, message, path, kind_rank FROM (");
        sql.append(union(timeline));
        sql.append(") events WHERE 1 = 1");
        String type = entityType(filter.entity());
        String id = entityId(filter.entity());
        if (type != null) {
            sql.append(" AND (");
            sql.append("((kind = 'usage' OR kind = 'log') AND entity_type = ?");
            values.add(type);
            if (id != null) {
                sql.append(" AND entity_id = ?");
                values.add(id);
            }
            if (filter.field() != null && !filter.field().isBlank()) {
                sql.append(" AND field = ?");
                values.add(filter.field());
            }
            sql.append(") OR run_id IN (");
            sql.append(touchedRuns(id != null, filter.field() != null && !filter.field().isBlank()));
            values.add(type);
            if (id != null) values.add(id);
            if (filter.field() != null && !filter.field().isBlank()) values.add(filter.field());
            values.add(type);
            if (id != null) values.add(id);
            sql.append(") OR step_exec_id IN (");
            sql.append(touchedSteps(id != null, filter.field() != null && !filter.field().isBlank()));
            values.add(type);
            if (id != null) values.add(id);
            if (filter.field() != null && !filter.field().isBlank()) values.add(filter.field());
            sql.append("))");
        } else if (filter.field() != null && !filter.field().isBlank()) {
            sql.append(" AND field = ?");
            values.add(filter.field());
        }
        if (filter.stepDef() != null && !filter.stepDef().isBlank()) {
            sql.append(" AND definition_id = ?");
            values.add(filter.stepDef());
        }
        if (filter.scenario() != null && !filter.scenario().isBlank()) {
            sql.append(" AND instr(scenario_key, ?) > 0");
            values.add(filter.scenario());
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            sql.append(" AND status = ? COLLATE NOCASE");
            values.add(filter.status());
        }
        if (filter.from() != null && !filter.from().isBlank()) {
            sql.append(" AND at >= ?");
            values.add(filter.from().trim());
        }
        if (filter.to() != null && !filter.to().isBlank()) {
            sql.append(" AND at <= ?");
            values.add(filter.to().trim());
        }
        if (filter.run() != null && !filter.run().isBlank()) {
            sql.append(" AND run_id = ?");
            values.add(filter.run().trim());
        }
        if (filter.feature() != null && !filter.feature().isBlank()) {
            String pattern = like(filter.feature().trim());
            sql.append(" AND (scenario_key LIKE ? ESCAPE '\\'");
            values.add(pattern);
            sql.append(" OR (kind = 'run' AND run_id IN (SELECT e.run_id FROM scenario_executions e WHERE e.scenario_key LIKE ? ESCAPE '\\')))");
            values.add(pattern);
        }
        if (filter.tag() != null && !filter.tag().isBlank()) {
            String token = tagToken(filter.tag());
            String pattern = likeToken(token);
            sql.append(" AND (");
            sql.append("EXISTS (SELECT 1 FROM scenarios sc WHERE ");
            sql.append(taggedScenario("scenario_key"));
            sql.append(" AND (' ' || sc.tags || ' ') LIKE ? ESCAPE '\\')");
            values.add(pattern);
            sql.append(" OR EXISTS (SELECT 1 FROM feature_docs d WHERE instr(scenario_key, d.file_path || '|') = 1 AND (' ' || d.tags || ' ') LIKE ? ESCAPE '\\')");
            values.add(pattern);
            sql.append(" OR EXISTS (SELECT 1 FROM example_blocks eb WHERE ");
            sql.append(taggedExamples("scenario_key"));
            sql.append(" AND (' ' || eb.tags || ' ') LIKE ? ESCAPE '\\')");
            values.add(pattern);
            sql.append(" OR (kind = 'run' AND run_id IN (SELECT e.run_id FROM scenario_executions e JOIN scenarios sc ON ");
            sql.append(taggedScenario("e.scenario_key"));
            sql.append(" WHERE (' ' || sc.tags || ' ') LIKE ? ESCAPE '\\'))");
            values.add(pattern);
            sql.append(" OR (kind = 'run' AND run_id IN (SELECT e.run_id FROM scenario_executions e JOIN feature_docs d ON instr(e.scenario_key, d.file_path || '|') = 1");
            sql.append(" WHERE (' ' || d.tags || ' ') LIKE ? ESCAPE '\\'))");
            values.add(pattern);
            sql.append(" OR (kind = 'run' AND run_id IN (SELECT e.run_id FROM scenario_executions e JOIN example_blocks eb ON ");
            sql.append(taggedExamples("e.scenario_key"));
            sql.append(" WHERE (' ' || eb.tags || ' ') LIKE ? ESCAPE '\\'))");
            values.add(pattern);
            sql.append(")");
        }
        if (filter.text() != null && !filter.text().isBlank()) {
            String pattern = like(filter.text().trim());
            sql.append(" AND (message LIKE ? ESCAPE '\\' OR step_text LIKE ? ESCAPE '\\' OR scenario_key LIKE ? ESCAPE '\\')");
            values.add(pattern);
            values.add(pattern);
            values.add(pattern);
        }
        if (after != null) {
            sql.append(" AND (at > ? OR (at = ? AND kind_rank > ?) OR (at = ? AND kind_rank = ? AND id > ?))");
            values.add(after.at());
            values.add(after.at());
            values.add(after.rank());
            values.add(after.at());
            values.add(after.rank());
            values.add(after.id());
        }
        if (before != null) {
            sql.append(" AND (at < ? OR (at = ? AND kind_rank < ?) OR (at = ? AND kind_rank = ? AND id < ?))");
            values.add(before.at());
            values.add(before.at());
            values.add(before.rank());
            values.add(before.at());
            values.add(before.rank());
            values.add(before.id());
        }
        if (page) {
            if (before != null) sql.append(" ORDER BY at DESC, kind_rank DESC, id DESC");
            else sql.append(" ORDER BY at ASC, kind_rank ASC, id ASC");
            sql.append(" LIMIT ? OFFSET ?");
            values.add(limit);
            values.add(before != null ? 0 : offset);
        }
        return new Sql(sql.toString(), values);
    }

    /**
     * A scenario tag matches that scenario's file and line. An outline example row
     * keeps the Examples line or the example-row line, which still falls before the next scenario.
     * An Examples tag matches only that block, up to the next Examples line or the next scenario.
     */
    private static String taggedScenario(String key) {
        return "instr(" + key + ", sc.file_path || '|') = 1"
                + " AND CAST(substr(" + key + ", length(sc.file_path) + 2) AS INTEGER) >= sc.line"
                + " AND CAST(substr(" + key + ", length(sc.file_path) + 2) AS INTEGER) < COALESCE("
                + "(SELECT MIN(nx.line) FROM scenarios nx WHERE nx.file_path = sc.file_path AND nx.line > sc.line), 2147483647)";
    }

    private static String taggedExamples(String key) {
        return "instr(" + key + ", eb.file_path || '|') = 1"
                + " AND CAST(substr(" + key + ", length(eb.file_path) + 2) AS INTEGER) >= eb.line"
                + " AND CAST(substr(" + key + ", length(eb.file_path) + 2) AS INTEGER) < COALESCE("
                + "(SELECT MIN(nx.line) FROM steps nx WHERE nx.file_path = eb.file_path"
                + " AND nx.scenario_ord = eb.scenario_ord AND nx.keyword = 'Examples' AND nx.line > eb.line),"
                + "(SELECT MIN(sc.line) FROM scenarios sc WHERE sc.file_path = eb.file_path AND sc.line > eb.line),"
                + "2147483647)";
    }

    private static String touchedRuns(boolean id, boolean field) {
        String usage = "SELECT e.run_id FROM entity_usages u JOIN step_executions s ON s.step_exec_id = u.step_exec_id"
                + " JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id WHERE u.entity_type = ?";
        if (id) usage += " AND u.entity_id = ?";
        if (field) usage += " AND u.field = ?";
        String log = " UNION SELECT g.run_id FROM log_entity_links l JOIN run_logs g ON g.id = l.log_id WHERE l.entity_type = ?";
        if (id) log += " AND l.entity_id = ?";
        return usage + log;
    }

    private static String touchedSteps(boolean id, boolean field) {
        String usage = "SELECT u.step_exec_id FROM entity_usages u WHERE u.entity_type = ?";
        if (id) usage += " AND u.entity_id = ?";
        if (field) usage += " AND u.field = ?";
        return usage;
    }

    private static String union(boolean timeline) {
        StringBuilder sql = new StringBuilder();
        if (timeline) {
            sql.append("""
                    SELECT started_at AS at, 'run' AS kind, run_id AS id, run_id AS run_id, status AS status,
                           scenario AS scenario_key, '' AS step_exec_id, '' AS step_text, '' AS definition_id,
                           '' AS entity_type, '' AS entity_id, '' AS field, '' AS operation, '' AS value_ref,
                           '' AS message, '' AS path, 0 AS kind_rank
                    FROM runs
                    UNION ALL
                    """);
        }
        sql.append("""
                SELECT s.started_at AS at, 'step' AS kind, s.step_exec_id AS id, e.run_id AS run_id, s.status AS status,
                       e.scenario_key AS scenario_key, s.step_exec_id AS step_exec_id, s.step_text AS step_text,
                       COALESCE(s.definition_id, '') AS definition_id,
                       '' AS entity_type, '' AS entity_id, '' AS field, '' AS operation, '' AS value_ref,
                       '' AS message, '' AS path, 1 AS kind_rank
                FROM step_executions s
                JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                UNION ALL
                SELECT u.logged_at, 'usage', CAST(u.usage_id AS TEXT), e.run_id, s.status, e.scenario_key,
                       u.step_exec_id, s.step_text, COALESCE(s.definition_id, ''),
                       u.entity_type, u.entity_id, COALESCE(u.field, ''), u.operation, COALESCE(u.value_ref, ''),
                       '', '', 2
                FROM entity_usages u
                JOIN step_executions s ON s.step_exec_id = u.step_exec_id
                JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                UNION ALL
                SELECT g.logged_at, 'log', CAST(l.log_id AS TEXT), g.run_id, runs.status, runs.scenario,
                       '', '', '',
                       l.entity_type, l.entity_id, '', '', '',
                       g.message, '', 3
                FROM log_entity_links l
                JOIN run_logs g ON g.id = l.log_id
                JOIN runs ON runs.run_id = g.run_id
                """);
        if (timeline) {
            sql.append("""
                    UNION ALL
                    SELECT a.retained_at, 'artifact', a.artifact_id, COALESCE(a.run_id, ''), COALESCE(runs.status, ''),
                           COALESCE(runs.scenario, ''), COALESCE(a.step_exec_id, ''), COALESCE(a.kind, ''), '',
                           '', '', '', '', '', '', a.path, 4
                    FROM artifacts a
                    LEFT JOIN runs ON runs.run_id = a.run_id
                    """);
        }
        return sql.toString();
    }

    private static Event event(ResultSet rows) throws SQLException {
        return new Event(
                text(rows.getString(1)), text(rows.getString(2)), text(rows.getString(3)), text(rows.getString(4)),
                text(rows.getString(5)), text(rows.getString(6)), text(rows.getString(7)), text(rows.getString(8)),
                text(rows.getString(9)), text(rows.getString(10)), text(rows.getString(11)), text(rows.getString(12)),
                text(rows.getString(13)), text(rows.getString(14)), text(rows.getString(15)), text(rows.getString(16)));
    }

    private static String json(String command, Page page, Filter filter, boolean timeline) {
        StringBuilder json = new StringBuilder();
        json.append("{\"command\":").append(quote(command));
        json.append(",\"count\":").append(page.events().size());
        json.append(",\"total\":").append(page.total());
        json.append(",\"limit\":").append(filter.limit());
        json.append(",\"offset\":").append(page.offset());
        if (timeline) {
            String cursor = page.events().isEmpty() ? "" : mark(page.events().get(page.events().size() - 1));
            String prev = page.events().isEmpty() ? "" : mark(page.events().get(0));
            json.append(",\"cursor\":").append(quote(cursor));
            json.append(",\"prev\":").append(quote(prev));
        }
        json.append(timeline ? ",\"events\":[" : ",\"rows\":[");
        for (int index = 0; index < page.events().size(); index++) {
            if (index > 0) json.append(',');
            Event event = page.events().get(index);
            json.append("{\"at\":").append(quote(event.at()));
            json.append(",\"kind\":").append(quote(event.kind()));
            json.append(",\"id\":").append(quote(event.id()));
            json.append(",\"runId\":").append(quote(event.runId()));
            json.append(",\"status\":").append(quote(event.status()));
            json.append(",\"scenarioKey\":").append(quote(event.scenarioKey()));
            json.append(",\"stepExecId\":").append(quote(event.stepExecId()));
            json.append(",\"stepText\":").append(quote(event.stepText()));
            json.append(",\"definitionId\":").append(quote(event.definitionId()));
            json.append(",\"entityType\":").append(quote(event.entityType()));
            json.append(",\"entityId\":").append(quote(event.entityId()));
            json.append(",\"field\":").append(quote(event.field()));
            json.append(",\"operation\":").append(quote(event.operation()));
            json.append(",\"valueRef\":").append(quote(event.valueRef()));
            json.append(",\"message\":").append(quote(event.message()));
            json.append(",\"path\":").append(quote(event.path()));
            json.append('}');
        }
        json.append("]}");
        return json.toString();
    }

    private static String mark(Event event) {
        return event.at() + "\u001f" + event.kind() + "\u001f" + event.id();
    }

    private static String text(List<Event> page) {
        StringBuilder text = new StringBuilder();
        for (Event event : page) {
            text.append(event.at()).append(' ').append(event.kind()).append(' ').append(event.id());
            if (!event.runId().isBlank()) text.append(' ').append(event.runId());
            if (!event.status().isBlank()) text.append(' ').append(event.status());
            if (!event.entityType().isBlank()) text.append(' ').append(event.entityType()).append('#').append(event.entityId());
            if (!event.stepText().isBlank()) text.append(' ').append(event.stepText());
            if (!event.path().isBlank()) text.append(' ').append(event.path());
            text.append('\n');
        }
        if (page.isEmpty()) text.append("events 0\n");
        return text.toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        String text = value == null ? "" : value;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"').toString();
    }

    private record Cursor(String at, int rank, String id) {
    }

    private static Cursor cursor(String raw, String flag) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split("\u001f", 3);
        if (parts.length != 3) throw new IllegalArgumentException(flag + " is not a valid timeline cursor");
        return new Cursor(parts[0], rank(parts[1]), parts[2]);
    }

    private static int rank(String kind) {
        return switch (kind) {
            case "run" -> 0;
            case "step" -> 1;
            case "usage" -> 2;
            case "log" -> 3;
            case "artifact" -> 4;
            default -> 5;
        };
    }

    private static String entityType(String entity) {
        if (entity == null || entity.isBlank()) return null;
        int hash = entity.indexOf('#');
        String type = hash < 0 ? entity.trim() : entity.substring(0, hash).trim();
        if (type.isEmpty()) throw new IllegalArgumentException("query entity must be Type or Type#id");
        return type;
    }

    private static String entityId(String entity) {
        if (entity == null) return null;
        int hash = entity.indexOf('#');
        if (hash < 0) return null;
        String id = entity.substring(hash + 1).trim();
        if (id.isEmpty()) throw new IllegalArgumentException("query entity must be Type or Type#id");
        return id;
    }

    private static Instant instant(String raw, String flag) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(flag + " must be ISO-8601: " + raw);
        }
    }

    private static String tagToken(String tag) {
        String trimmed = tag.trim();
        if (trimmed.startsWith("@")) return trimmed;
        return "@" + trimmed;
    }

    private static String likeToken(String token) {
        return "% " + escapeLike(token) + " %";
    }

    private static String like(String raw) {
        return "%" + escapeLike(raw) + "%";
    }

    private static String escapeLike(String raw) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            if (ch == '\\' || ch == '%' || ch == '_') out.append('\\');
            out.append(ch);
        }
        return out.toString();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private record Event(
            String at,
            String kind,
            String id,
            String runId,
            String status,
            String scenarioKey,
            String stepExecId,
            String stepText,
            String definitionId,
            String entityType,
            String entityId,
            String field,
            String operation,
            String valueRef,
            String message,
            String path
    ) {
    }
}
