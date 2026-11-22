package tools.dscode.common.recording;

import tools.dscode.common.agentfast.PushChannel;
import tools.dscode.control.protocol.AgentEvents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One interaction row on the push channel and on the recording stream.
 * {@code event} is {@code interaction}. {@code type} is the gesture
 * (click, input, change, select, key, hover, drag, focus, check).
 */
public final class InteractionLog {
    public static final String STREAM = ".pickleball/recordings/stream.jsonl";
    static final List<String> KINDS = List.of(
            "click", "input", "change", "select", "key", "hover", "drag", "focus", "check"
    );

    private InteractionLog() {
    }

    public static Path stream(Path project) {
        Path root = project == null ? Path.of("") : project;
        return root.resolve(STREAM);
    }

    /** Normalizes and stores one interaction. Returns the stored string fields. */
    public static Map<String, String> accept(Path project, Map<String, String> raw) {
        Map<String, String> row = normalize(project, raw);
        PushChannel.accept(project, row);
        appendStream(project, row);
        return row;
    }

    public static Map<String, String> normalize(Path project, Map<String, String> raw) {
        Map<String, String> row = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((key, value) -> {
                if (key != null && value != null) row.put(key, value);
            });
        }
        String kind = first(row.get("kind"), row.get("type"), "click");
        if ("interaction".equalsIgnoreCase(kind)) kind = first(row.get("kind"), "click");
        row.put("type", kind);
        row.put("kind", kind);
        row.put("event", "interaction");
        row.putIfAbsent("source", "human");
        if ("true".equalsIgnoreCase(row.get("sensitive"))) {
            row.put("value", "filled");
            row.remove("oldValue");
        }
        row.putIfAbsent("at", Instant.now().toString());
        String session = row.get("sessionId");
        if (session == null || session.isBlank()) {
            String bound = RecordingSession.sessionId(project);
            if (bound != null && !bound.isBlank()) row.put("sessionId", bound);
        }
        row.putIfAbsent("frame", "top");
        row.putIfAbsent("target", first(row.get("name"), row.get("text"), row.get("id"), kind));
        return row;
    }

    public static List<Map<String, String>> read(Path project) {
        return AgentEvents.lines(project).stream()
                .filter(InteractionLog::isInteraction)
                .toList();
    }

    /** Lines of the dedicated stream, in file order. */
    public static List<Map<String, String>> streamLines(Path project) {
        Path file = stream(project);
        if (!Files.isRegularFile(file)) return List.of();
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(AgentEvents::parseObject)
                    .filter(row -> !row.isEmpty())
                    .toList();
        } catch (IOException failure) {
            return List.of();
        }
    }

    public static boolean isInteraction(Map<String, String> row) {
        if (row == null || row.isEmpty()) return false;
        if ("interaction".equalsIgnoreCase(row.get("event"))) return true;
        String type = first(row.get("type"), row.get("kind"), "");
        return KINDS.contains(type.toLowerCase(java.util.Locale.ROOT))
                && "interaction".equalsIgnoreCase(row.get("event"));
    }

    static String first(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static void appendStream(Path project, Map<String, String> row) {
        Path file = stream(project);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    line(row),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // The push channel is the durable copy. A later read reports a short stream.
        }
    }

    private static String line(Map<String, String> row) {
        StringBuilder json = new StringBuilder("{");
        boolean any = false;
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            if (any) json.append(',');
            any = true;
            json.append('"').append(escape(entry.getKey())).append("\":\"");
            json.append(escape(entry.getValue())).append('"');
        }
        json.append("}\n");
        return json.toString();
    }

    private static String escape(String value) {
        String raw = value == null ? "" : value;
        StringBuilder json = new StringBuilder();
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (ch < 0x20) json.append(String.format("\\u%04x", (int) ch));
                    else json.append(ch);
                }
            }
        }
        return json.toString();
    }
}
