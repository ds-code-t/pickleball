package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Remembers the chat id at session start so later reads skip earlier runs. */
final class ChatCursor {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ChatCursor() {
    }

    static void arm(Path project, String runId, String channel) throws IOException {
        String useChannel = channel == null || channel.isBlank() ? "run" : channel;
        String after = ChatLog.latestId(project, runId, useChannel);
        Path file = file(project, runId, useChannel);
        Files.createDirectories(file.getParent());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", useChannel);
        body.put("after", after);
        Files.writeString(file, JSON.writeValueAsString(body) + "\n");
    }

    static String after(Path project, String runId, String channel) {
        String useChannel = channel == null || channel.isBlank() ? "run" : channel;
        Path file = file(project, runId, useChannel);
        if (!Files.isRegularFile(file)) return null;
        try {
            String after = JSON.readTree(file.toFile()).path("after").asText("");
            return after.isBlank() ? null : after;
        } catch (IOException failure) {
            return null;
        }
    }

    private static Path file(Path project, String runId, String channel) {
        String safe = channel.replaceAll("[^A-Za-z0-9._-]", "_");
        Path root = project == null ? Path.of("") : project;
        String id = runId == null || runId.isBlank() ? "session" : runId;
        return root.resolve(".pickleball").resolve("runs").resolve(id).resolve("agent").resolve("chat").resolve(safe + ".cursor.json");
    }
}
