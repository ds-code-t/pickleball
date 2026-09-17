package tools.dscode.workbench.nav;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** One navigation request shared by Explorer Open, Report clicks, and MCP workbench_go. */
public record WorkbenchGoLink(
        String to,
        String runId,
        long eventSeq,
        String nodeId,
        String path,
        int line,
        int column,
        String kind,
        String mapReference,
        String key,
        String investigationId,
        String section,
        String label
) {
    public WorkbenchGoLink {
        to = normalizeTo(to);
        runId = runId == null ? "" : runId;
        eventSeq = Math.max(0, eventSeq);
        nodeId = nodeId == null ? "" : nodeId;
        path = path == null ? "" : path.replace('\\', '/');
        line = Math.max(0, line);
        column = Math.max(0, column);
        kind = kind == null ? "" : kind;
        mapReference = mapReference == null ? "" : mapReference;
        key = key == null ? "" : key;
        investigationId = investigationId == null ? "" : investigationId;
        section = section == null ? "" : section;
        label = label == null ? "" : label;
    }

    public static WorkbenchGoLink fromMap(Map<String, ?> raw) {
        Map<String, ?> source = raw == null ? Map.of() : raw;
        return new WorkbenchGoLink(
                text(source, "to"),
                text(source, "runId", "run"),
                longValue(source, "eventSeq", "seq"),
                text(source, "nodeId", "node"),
                text(source, "path"),
                (int) longValue(source, "line"),
                (int) longValue(source, "column"),
                text(source, "kind"),
                text(source, "mapReference"),
                text(source, "key"),
                text(source, "investigationId"),
                text(source, "section"),
                text(source, "label")
        );
    }

    public static WorkbenchGoLink parse(String link) {
        if (link == null || link.isBlank()) {
            return fromMap(Map.of());
        }
        String trimmed = link.trim();
        if (trimmed.startsWith("wb://")) {
            return parseUri(trimmed);
        }
        return fromMap(Map.of("path", trimmed));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("to", to);
        map.put("runId", runId);
        map.put("eventSeq", eventSeq);
        map.put("nodeId", nodeId);
        map.put("path", path);
        map.put("line", line);
        map.put("column", column);
        map.put("kind", kind);
        map.put("mapReference", mapReference);
        map.put("key", key);
        map.put("investigationId", investigationId);
        map.put("section", section);
        map.put("label", label);
        return map;
    }

    private static WorkbenchGoLink parseUri(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? uri.getAuthority() : uri.getHost();
            Map<String, String> query = query(uri.getRawQuery());
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("to", host == null ? "" : host);
            raw.put("runId", first(query, "runId", "run"));
            raw.put("eventSeq", first(query, "eventSeq", "seq"));
            raw.put("nodeId", first(query, "nodeId", "node"));
            raw.put("path", first(query, "path"));
            raw.put("line", first(query, "line"));
            raw.put("column", first(query, "column"));
            raw.put("kind", first(query, "kind"));
            raw.put("mapReference", first(query, "mapReference"));
            raw.put("key", first(query, "key"));
            raw.put("investigationId", first(query, "investigationId"));
            raw.put("section", first(query, "section"));
            raw.put("label", first(query, "label"));
            return fromMap(raw);
        } catch (RuntimeException ignored) {
            return fromMap(Map.of("path", value));
        }
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return query;
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            String name = eq < 0 ? part : part.substring(0, eq);
            String value = eq < 0 ? "" : part.substring(eq + 1);
            query.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }

    private static String first(Map<String, String> query, String... keys) {
        for (String key : keys) {
            String value = query.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String text(Map<String, ?> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    private static long longValue(Map<String, ?> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof Number number) return number.longValue();
            if (value != null && !String.valueOf(value).isBlank()) {
                try {
                    return Long.parseLong(String.valueOf(value).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    private static String normalizeTo(String to) {
        String value = to == null || to.isBlank() ? "editor" : to.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "explorer", "editor", "mapping", "report", "terminal" -> value;
            default -> "editor";
        };
    }
}
