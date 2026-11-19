package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Remembers the last agent-event line this session and agent already returned. */
final class EventCursor {
    private static final ObjectMapper JSON = new ObjectMapper();

    private EventCursor() {
    }

    static int after(Path project, String runId, String agentId) {
        Path file = file(project, runId, agentId);
        if (!Files.isRegularFile(file)) return 0;
        try {
            return JSON.readTree(file.toFile()).path("after").asInt(0);
        } catch (IOException failure) {
            return 0;
        }
    }

    static void advance(Path project, String runId, String agentId, int afterLine) throws IOException {
        if (afterLine < 0) return;
        Path file = file(project, runId, agentId);
        Files.createDirectories(file.getParent());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("after", afterLine);
        Files.writeString(file, JSON.writeValueAsString(body) + "\n");
    }

    /** Arms the cursor at the current end of the event file so earlier lines stay unread. */
    static void arm(Path project, String runId, String agentId) throws IOException {
        advance(project, runId, agentId, tools.dscode.control.protocol.AgentEvents.lineCount(project));
    }

    private static Path file(Path project, String runId, String agentId) {
        String agent = agentId == null || agentId.isBlank() ? "session" : agentId;
        String safe = agent.replaceAll("[^A-Za-z0-9._-]", "_");
        Path root = project == null ? Path.of("") : project;
        String id = runId == null || runId.isBlank() ? "session" : runId;
        return root.resolve(".pickleball").resolve("runs").resolve(id).resolve("agent").resolve("events").resolve(safe + ".cursor.json");
    }
}
