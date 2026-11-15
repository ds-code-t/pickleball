package tools.dscode.dalton;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Read-only query and timeline over linked tracking rows.
 * A missing database is an empty result and is not created.
 */
public final class DaltonTrack {
    private DaltonTrack() {
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
            int offset
    ) {
        public Filter {
            if (limit < 0) throw new IllegalArgumentException("limit must be zero or greater");
            if (offset < 0) throw new IllegalArgumentException("offset must be zero or greater");
        }
    }

    public static String query(Path project, Filter filter, boolean json) throws SQLException {
        List<Event> page = page(project, filter, false);
        int total = matched(project, filter, false).size();
        DaltonLog.log(project, "query", "query", "rows=" + page.size());
        return json ? json("query", page, filter, total, false) : text(page);
    }

    public static String timeline(Path project, Filter filter, boolean json) throws SQLException {
        List<Event> page = page(project, filter, true);
        int total = matched(project, filter, true).size();
        DaltonLog.log(project, "track", "timeline", "events=" + page.size());
        return json ? json("timeline", page, filter, total, true) : text(page);
    }

    private static List<Event> page(Path project, Filter filter, boolean timeline) throws SQLException {
        List<Event> matched = matched(project, filter, timeline);
        int start = Math.min(filter.offset(), matched.size());
        int end = Math.min(matched.size(), start + filter.limit());
        return matched.subList(start, end);
    }

    private static List<Event> matched(Path project, Filter filter, boolean timeline) throws SQLException {
        List<Event> events = load(project, timeline);
        String type = entityType(filter.entity());
        String id = entityId(filter.entity());
        Instant from = instant(filter.from(), "--from");
        Instant to = instant(filter.to(), "--to");
        List<Event> touched = new ArrayList<>();
        for (Event event : events) {
            if (!entityMatches(event, type, id, filter.field())) continue;
            touched.add(event);
        }
        java.util.Set<String> runs = new java.util.LinkedHashSet<>();
        java.util.Set<String> steps = new java.util.LinkedHashSet<>();
        if (type != null) {
            for (Event event : touched) {
                if (!event.runId().isBlank()) runs.add(event.runId());
                if (!event.stepExecId().isBlank()) steps.add(event.stepExecId());
            }
        }
        List<Event> matched = new ArrayList<>();
        for (Event event : events) {
            if (type != null) {
                boolean direct = entityMatches(event, type, id, filter.field())
                        && ("usage".equals(event.kind()) || "log".equals(event.kind()));
                boolean parent = runs.contains(event.runId()) || steps.contains(event.stepExecId());
                if (!direct && !parent) continue;
            } else if (filter.field() != null && !filter.field().isBlank()
                    && !filter.field().equals(event.field())) {
                continue;
            }
            if (filter.stepDef() != null && !filter.stepDef().isBlank()
                    && !filter.stepDef().equals(event.definitionId())) {
                continue;
            }
            if (filter.scenario() != null && !filter.scenario().isBlank()
                    && !event.scenarioKey().contains(filter.scenario())) {
                continue;
            }
            if (filter.status() != null && !filter.status().isBlank()
                    && !filter.status().equalsIgnoreCase(event.status())) {
                continue;
            }
            Instant at = parse(event.at());
            if (from != null && (at == null || at.isBefore(from))) continue;
            if (to != null && (at == null || at.isAfter(to))) continue;
            if (filter.cursor() != null && !filter.cursor().isBlank() && !afterCursor(event, filter.cursor())) continue;
            matched.add(event);
        }
        matched.sort(ORDER);
        return matched;
    }

    private static boolean entityMatches(Event event, String type, String id, String field) {
        if (type == null) return true;
        if (!type.equals(event.entityType())) return false;
        if (id != null && !id.equals(event.entityId())) return false;
        return field == null || field.isBlank() || field.equals(event.field());
    }

    private static boolean afterCursor(Event event, String cursor) {
        String[] parts = cursor.split("\u001f", 3);
        if (parts.length != 3) throw new IllegalArgumentException("timeline cursor is not valid");
        Event mark = new Event(parts[0], parts[1], parts[2], "", "", "", "", "", "", "", "", "", "", "", "", "");
        return ORDER.compare(event, mark) > 0;
    }

    private static final Comparator<Event> ORDER = (left, right) -> {
        int at = compareAt(left.at(), right.at());
        if (at != 0) return at;
        int kind = Integer.compare(rank(left.kind()), rank(right.kind()));
        if (kind != 0) return kind;
        return left.id().compareTo(right.id());
    };

    private static int compareAt(String left, String right) {
        Instant a = parse(left);
        Instant b = parse(right);
        if (a != null && b != null) return a.compareTo(b);
        return (left == null ? "" : left).compareTo(right == null ? "" : right);
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

    private static List<Event> load(Path project, boolean timeline) throws SQLException {
        if (project == null || !Files.isRegularFile(DaltonPaths.database(project))) return List.of();
        List<Event> events = new ArrayList<>();
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            Connection connection = database.connection();
            if (timeline) loadRuns(connection, events);
            loadSteps(connection, events);
            loadUsages(connection, events);
            loadLogs(connection, events);
            if (timeline) loadArtifacts(connection, events);
        }
        return events;
    }

    private static void loadRuns(Connection connection, List<Event> events) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT run_id, started_at, status, scenario FROM runs");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                events.add(event(rows.getString(2), "run", rows.getString(1), rows.getString(1),
                        rows.getString(3), text(rows.getString(4)), "", "", "", "", "", "", "", "", "", ""));
            }
        }
    }

    private static void loadSteps(Connection connection, List<Event> events) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.started_at, s.step_exec_id, e.run_id, s.status, e.scenario_key, s.step_text, s.definition_id
                FROM step_executions s
                JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String step = text(rows.getString(2));
                events.add(event(rows.getString(1), "step", step, rows.getString(3), rows.getString(4),
                        rows.getString(5), step, rows.getString(6), rows.getString(7), "", "", "", "", "", "", ""));
            }
        }
    }

    private static void loadUsages(Connection connection, List<Event> events) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT u.logged_at, u.usage_id, e.run_id, s.status, e.scenario_key, u.step_exec_id, s.step_text,
                       s.definition_id, u.entity_type, u.entity_id, u.field, u.operation, u.value_ref
                FROM entity_usages u
                JOIN step_executions s ON s.step_exec_id = u.step_exec_id
                JOIN scenario_executions e ON e.exec_id = s.scenario_exec_id
                """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                events.add(event(rows.getString(1), "usage", String.valueOf(rows.getLong(2)), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8),
                        rows.getString(9), rows.getString(10), rows.getString(11), rows.getString(12), rows.getString(13),
                        "", ""));
            }
        }
    }

    private static void loadLogs(Connection connection, List<Event> events) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT g.logged_at, l.log_id, g.run_id, runs.status, runs.scenario, l.entity_type, l.entity_id, g.message
                FROM log_entity_links l
                JOIN run_logs g ON g.id = l.log_id
                JOIN runs ON runs.run_id = g.run_id
                """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                events.add(event(rows.getString(1), "log", String.valueOf(rows.getLong(2)), rows.getString(3),
                        rows.getString(4), rows.getString(5), "", "", "", rows.getString(6), rows.getString(7),
                        "", "", "", rows.getString(8), ""));
            }
        }
    }

    private static void loadArtifacts(Connection connection, List<Event> events) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.retained_at, a.artifact_id, a.run_id, runs.status, runs.scenario, a.step_exec_id, a.kind, a.path
                FROM artifacts a
                LEFT JOIN runs ON runs.run_id = a.run_id
                """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                events.add(event(rows.getString(1), "artifact", rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7), "",
                        "", "", "", "", "", "", rows.getString(8)));
            }
        }
    }

    private static Event event(
            String at, String kind, String id, String runId, String status, String scenarioKey, String stepExecId,
            String stepText, String definitionId, String entityType, String entityId, String field, String operation,
            String valueRef, String message, String path
    ) {
        return new Event(text(at), kind, text(id), text(runId), text(status), text(scenarioKey), text(stepExecId),
                text(stepText), text(definitionId), text(entityType), text(entityId), text(field), text(operation),
                text(valueRef), text(message), text(path));
    }

    private static String json(String command, List<Event> page, Filter filter, int total, boolean timeline) {
        StringBuilder json = new StringBuilder();
        json.append("{\"command\":").append(quote(command));
        json.append(",\"count\":").append(page.size());
        json.append(",\"total\":").append(total);
        json.append(",\"limit\":").append(filter.limit());
        json.append(",\"offset\":").append(filter.offset());
        if (timeline) {
            String cursor = page.isEmpty() ? "" : page.get(page.size() - 1).at() + "\u001f"
                    + page.get(page.size() - 1).kind() + "\u001f" + page.get(page.size() - 1).id();
            json.append(",\"cursor\":").append(quote(cursor));
        }
        json.append(timeline ? ",\"events\":[" : ",\"rows\":[");
        for (int index = 0; index < page.size(); index++) {
            if (index > 0) json.append(',');
            Event event = page.get(index);
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

    private static Instant parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException failure) {
            return null;
        }
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
