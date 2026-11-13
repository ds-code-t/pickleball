package tools.dscode.common.agentsurface;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON status for {@code popup-status}. */
public final class PopupStatus {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PopupStatus() {
    }

    public static Map<String, Object> read(Path project, String runId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("op", "popup-status");
        Path file = PopupLog.popupDir(project, runId).resolve("host.json");
        if (!Files.isRegularFile(file)) {
            body.put("state", "closed");
            body.put("reused", false);
            body.put("warnings", PopupLog.warnings(project, runId));
            return body;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> stored = JSON.readValue(file.toFile(), Map.class);
            body.putAll(stored);
        } catch (Exception failure) {
            body.put("state", "unreadable");
            body.put("error", failure.getMessage());
        }
        body.put("warnings", PopupLog.warnings(project, runId));
        return body;
    }

    public static String json(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception failure) {
            return "{\"ok\":false}";
        }
    }
}
