package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Flags and files for one snapshot build. {@code from(JsonNode)} is the MCP adapter. */
public final class SnapshotOptions {
    private final String file;
    private final String screenshot;
    private final String outDir;
    private final String sessionId;
    private final String platform;
    private final String id;
    private final Map<String, String> flags;

    public SnapshotOptions() {
        this(Map.of());
    }

    private SnapshotOptions(Map<String, String> flags) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (flags != null) copy.putAll(flags);
        this.flags = Map.copyOf(copy);
        this.file = text(copy, "file");
        this.screenshot = text(copy, "screenshot");
        this.outDir = first(copy, "out-dir", "outDir");
        this.sessionId = first(copy, "session", "session-id", "id");
        this.platform = text(copy, "platform");
        this.id = text(copy, "snapshot-id");
    }

    public static SnapshotOptions from(JsonNode node) {
        Map<String, String> flags = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue() == null || entry.getValue().isNull() || entry.getValue().isContainerNode()) return;
                flags.put(entry.getKey(), entry.getValue().asText(""));
            });
        }
        return new SnapshotOptions(flags);
    }

    public static SnapshotOptions fromFlags(Map<String, String> flags) {
        return new SnapshotOptions(flags);
    }

    public String file() {
        return file;
    }

    public String screenshot() {
        return screenshot;
    }

    public String outDir() {
        return outDir;
    }

    public String sessionId() {
        return sessionId;
    }

    public String platform() {
        return platform;
    }

    public String id() {
        return id;
    }

    public Map<String, String> flags() {
        return flags;
    }

    private static String text(Map<String, String> flags, String key) {
        String value = flags.get(key);
        return value == null ? "" : value;
    }

    private static String first(Map<String, String> flags, String... keys) {
        for (String key : keys) {
            String value = flags.get(key);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }
}
