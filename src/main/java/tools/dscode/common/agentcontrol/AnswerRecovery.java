package tools.dscode.common.agentcontrol;

import tools.dscode.control.protocol.AgentEvents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An answer that the page already pushed or stored. {@code ask-and-wait} returns it
 * even when the live worker call times out or cannot be reached.
 */
public final class AnswerRecovery {
    private AnswerRecovery() {
    }

    /**
     * The newest answer for {@code questionId} at or after {@code sinceMillis}.
     * A blank question id accepts the newest matching answer. A value that is every
     * choice label joined together is ignored.
     */
    public static Map<String, String> find(Path project, String runId, long sinceMillis, String questionId, List<String> choices) {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.addAll(AgentEvents.lines(project));
        rows.addAll(readObjects(AgentStore.root(project, runId).resolve("overlay-events.jsonl")));
        Map<String, String> hit = null;
        long hitAt = Long.MIN_VALUE;
        for (Map<String, String> row : rows) {
            if (!"answer".equalsIgnoreCase(row.get("type")) && !"answer".equalsIgnoreCase(row.get("event"))) continue;
            if (questionId != null && !questionId.isBlank()) {
                String id = row.get("id");
                if (id != null && !id.isBlank() && !questionId.equals(id)) continue;
            }
            String value = row.get("choice");
            if (value == null || value.isBlank()) value = row.get("value");
            value = value == null ? "" : value.replaceAll("\\s+", " ").trim();
            if (!oneChoice(value, choices)) continue;
            long at = epoch(row.get("at"));
            if (sinceMillis > 0 && at > 0 && at + 2000 < sinceMillis) continue;
            if (hit == null || at >= hitAt) {
                hit = new LinkedHashMap<>(row);
                hit.put("value", value);
                hit.put("choice", value);
                hit.put("type", "answer");
                hitAt = at;
            }
        }
        return hit;
    }

    static boolean oneChoice(String value, List<String> choices) {
        if (value == null || value.isBlank()) return false;
        if (choices == null || choices.isEmpty()) return true;
        for (String choice : choices) if (choice.equals(value)) return true;
        return false;
    }

    private static long epoch(String at) {
        if (at == null || at.isBlank()) return 0;
        try {
            return Instant.parse(at).toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static List<Map<String, String>> readObjects(Path file) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (!Files.isRegularFile(file)) return rows;
        try {
            for (String line : Files.readAllLines(file)) {
                Map<String, String> row = AgentEvents.parseObject(line);
                if (!row.isEmpty()) rows.add(row);
            }
        } catch (IOException ignored) {
            return rows;
        }
        return rows;
    }
}
