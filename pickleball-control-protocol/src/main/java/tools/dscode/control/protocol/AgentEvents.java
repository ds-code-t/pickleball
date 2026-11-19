package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

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

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
