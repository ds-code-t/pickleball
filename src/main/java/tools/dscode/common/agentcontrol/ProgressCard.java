package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** A progress card. {@code pct} is 0 through 100. */
public final class ProgressCard {
    private ProgressCard() {
    }

    public static Map<String, Object> write(Path project, String session, String pct, String label) throws IOException {
        int value;
        try {
            value = Integer.parseInt(pct == null ? "" : pct.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("error: pct is 0 through 100\nfix: progress --pct=0 through 100");
        }
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("error: pct is 0 through 100\nfix: progress --pct=0 through 100");
        }
        String useSession = session == null || session.isBlank() ? "session" : session.trim();
        ObjectNode card = AgentStore.JSON.createObjectNode();
        card.put("session", useSession);
        card.put("pct", value);
        card.put("label", label == null ? "" : label.trim());
        Path file = file(project, useSession);
        Files.createDirectories(file.getParent());
        Files.writeString(file, AgentStore.JSON.writeValueAsString(card), StandardCharsets.UTF_8);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("session", useSession);
        map.put("pct", value);
        map.put("label", card.path("label").asText(""));
        return map;
    }

    private static Path file(Path project, String session) {
        Path root = project == null ? Path.of("") : project;
        String safe = session.replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(".pickleball").resolve("workbench-ui").resolve("progress").resolve(safe + ".json");
    }
}
