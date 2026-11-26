package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** One point row. The page button calls the {@code point} verb, which writes this file. */
public final class PointLog {
    private PointLog() {
    }

    public static Map<String, Object> record(Path project, String session, String handle, String x, String y) throws IOException {
        String useSession = session == null || session.isBlank() ? "session" : session.trim();
        ObjectNode row = AgentStore.JSON.createObjectNode();
        row.put("session", useSession);
        row.put("handle", handle == null ? "" : handle.trim());
        if (x == null || x.isBlank()) row.putNull("x");
        else row.put("x", x.trim());
        if (y == null || y.isBlank()) row.putNull("y");
        else row.put("y", y.trim());
        row.put("at", AgentStore.now());
        Path file = file(project, useSession);
        Files.createDirectories(file.getParent());
        Files.writeString(file, AgentStore.JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("session", useSession);
        map.put("handle", row.path("handle").asText(""));
        map.put("x", row.path("x").isNull() ? null : row.path("x").asText());
        map.put("y", row.path("y").isNull() ? null : row.path("y").asText());
        map.put("at", row.path("at").asText(""));
        return map;
    }

    private static Path file(Path project, String session) {
        Path root = project == null ? Path.of("") : project;
        String safe = session.replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(".pickleball").resolve("workbench-ui").resolve("points").resolve(safe + ".jsonl");
    }
}
