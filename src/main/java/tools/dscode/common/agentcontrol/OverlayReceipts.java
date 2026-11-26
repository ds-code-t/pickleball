package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Overlay read receipts. {@code read_at} stays null until {@link #ack}.
 * {@code overlay-poll} returns the same field.
 */
public final class OverlayReceipts {
    private OverlayReceipts() {
    }

    public static void record(Path project, String session, String id) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("error: overlay receipt needs an id\nfix: pass the overlay id");
        }
        List<ObjectNode> rows = read(project, session);
        if (find(rows, id.trim()) != null) return;
        ObjectNode row = AgentStore.JSON.createObjectNode();
        row.put("id", id.trim());
        row.putNull("read_at");
        rows.add(row);
        write(project, session, rows);
    }

    public static void ack(Path project, String session, String id) throws IOException {
        List<ObjectNode> rows = read(project, session);
        ObjectNode row = find(rows, id == null ? "" : id.trim());
        if (row == null) {
            record(project, session, id);
            rows = read(project, session);
            row = find(rows, id == null ? "" : id.trim());
        }
        if (row == null) return;
        row.put("read_at", AgentStore.now());
        write(project, session, rows);
    }

    public static List<Map<String, Object>> poll(Path project, String session) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (ObjectNode node : read(project, session)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", node.path("id").asText(""));
                row.put("type", "overlay");
                if (node.path("read_at").isNull() || node.path("read_at").asText("").isBlank()) row.put("read_at", null);
                else row.put("read_at", node.path("read_at").asText());
                rows.add(row);
            }
        } catch (IOException failure) {
            return List.of();
        }
        return rows;
    }

    /**
     * Stamps {@code read_at} onto an existing event with the same id, or appends the receipt.
     * A blank session uses the current spine session.
     */
    public static void merge(Path project, String session, List<Map<String, Object>> events) {
        if (events == null) return;
        String use = session;
        if (use == null || use.isBlank()) use = SpineSession.current(project);
        for (Map<String, Object> row : poll(project, use)) {
            Object id = row.get("id");
            boolean found = false;
            if (id != null) {
                for (Map<String, Object> event : events) {
                    if (id.equals(event.get("id"))) {
                        event.put("read_at", row.get("read_at"));
                        found = true;
                        break;
                    }
                }
            }
            if (!found) events.add(row);
        }
    }

    private static ObjectNode find(List<ObjectNode> rows, String id) {
        for (ObjectNode row : rows) if (id.equals(row.path("id").asText())) return row;
        return null;
    }

    private static List<ObjectNode> read(Path project, String session) throws IOException {
        List<ObjectNode> rows = new ArrayList<>();
        Path file = file(project, session);
        if (!Files.isRegularFile(file)) return rows;
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) continue;
            JsonNode node = AgentStore.JSON.readTree(line);
            if (node.isObject()) rows.add((ObjectNode) node);
        }
        return rows;
    }

    private static void write(Path project, String session, List<ObjectNode> rows) throws IOException {
        Path file = file(project, session);
        Files.createDirectories(file.getParent());
        StringBuilder text = new StringBuilder();
        for (ObjectNode row : rows) {
            text.append(AgentStore.JSON.writeValueAsString(row)).append('\n');
        }
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    }

    private static Path file(Path project, String session) {
        Path root = project == null ? Path.of("") : project;
        String raw = session == null || session.isBlank() ? "session" : session.trim();
        String safe = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(".pickleball").resolve("workbench-ui").resolve("overlay").resolve(safe + ".jsonl");
    }
}
