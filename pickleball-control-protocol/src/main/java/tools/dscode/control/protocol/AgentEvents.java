package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Append-only agent events the next ui-poll or chat-poll can read. No tokens. */
public final class AgentEvents {
    public static final String RELATIVE = ".pickleball/agent-events.jsonl";

    private AgentEvents() {
    }

    public static Path file(Path project) {
        Path root = project == null ? Path.of("") : project;
        return root.resolve(RELATIVE);
    }

    public static void append(Path project, String event, String sessionId) {
        String name = event == null || event.isBlank() ? "event" : event;
        String session = sessionId == null ? "" : sessionId;
        String line = "{\"event\":\"" + escape(name) + "\",\"sessionId\":\"" + escape(session)
                + "\",\"at\":\"" + Instant.now() + "\"}\n";
        Path target = file(project);
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(
                    target,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // The stop path still kills the process when the event file cannot be written.
        }
    }

    public record Slice(List<Map<String, String>> events, int next) {
    }

    /** How many lines are in the event file. Zero when the file is absent. */
    public static int lineCount(Path project) {
        Path target = file(project);
        if (!Files.isRegularFile(target)) return 0;
        try {
            return Files.readAllLines(target, StandardCharsets.UTF_8).size();
        } catch (IOException ignored) {
            return 0;
        }
    }

    /**
     * Lines after {@code afterLine}. A {@code browser_lost} line, and any line whose
     * session id is blank, is host-level: every cursor receives it once. {@code session_ended}
     * is delivered only when {@code sessionId} equals that line. {@code next} is the last
     * line number scanned, so a cursor moves past lines that belong to other sessions.
     */
    public static Slice after(Path project, String sessionId, int afterLine) {
        List<Map<String, String>> rows = new ArrayList<>();
        Path target = file(project);
        int next = Math.max(0, afterLine);
        if (!Files.isRegularFile(target)) {
            return new Slice(rows, next);
        }
        String wanted = sessionId == null ? "" : sessionId;
        try {
            int index = 0;
            for (String line : Files.readAllLines(target, StandardCharsets.UTF_8)) {
                index++;
                if (index <= afterLine) continue;
                next = index;
                String event = jsonField(line, "event");
                if (!"browser_lost".equals(event) && !"session_ended".equals(event)) continue;
                String lineSession = jsonField(line, "sessionId");
                boolean hostLevel = "browser_lost".equals(event) || lineSession.isBlank();
                if (!hostLevel && !wanted.equals(lineSession)) continue;
                Map<String, String> row = new LinkedHashMap<>();
                row.put("event", event);
                row.put("sessionId", lineSession);
                row.put("at", jsonField(line, "at"));
                rows.add(row);
            }
        } catch (IOException ignored) {
            return new Slice(List.of(), next);
        }
        return new Slice(rows, next);
    }

    /** Last {@code browser_lost} and {@code session_ended} lines, oldest first. At most {@code limit}. */
    public static List<Map<String, String>> tail(Path project, int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        Path target = file(project);
        if (!Files.isRegularFile(target)) return rows;
        try {
            for (String line : Files.readAllLines(target, StandardCharsets.UTF_8)) {
                String event = jsonField(line, "event");
                if (!"browser_lost".equals(event) && !"session_ended".equals(event)) continue;
                Map<String, String> row = new LinkedHashMap<>();
                row.put("event", event);
                row.put("sessionId", jsonField(line, "sessionId"));
                row.put("at", jsonField(line, "at"));
                rows.add(row);
            }
        } catch (IOException ignored) {
            return List.of();
        }
        int keep = limit <= 0 ? rows.size() : Math.min(limit, rows.size());
        if (keep == rows.size()) return rows;
        return List.copyOf(rows.subList(rows.size() - keep, rows.size()));
    }

    private static String jsonField(String line, String name) {
        String key = "\"" + name + "\":\"";
        int start = line.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = line.indexOf('"', start);
        if (end < 0) return "";
        return line.substring(start, end);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
