package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

/**
 * Toggleable, per-entity, date-stamped, size-capped agent-fast log.
 * Off unless {@code pkb_agent_log} (or {@code PKB_AGENT_LOG}) is {@code true},
 * {@code on}, {@code all}, or a comma-separated entity list.
 * {@code pkb_agent_log_entity} limits lines when set.
 * The file is {@code .pickleball/logs/agent-fast.log}. Callers must not pass tokens.
 */
public final class AgentFastLog {
    public static final int DEFAULT_MAX_BYTES = 1_048_576;

    private AgentFastLog() {
    }

    public static boolean enabled(String entity) {
        String raw = setting("pkb_agent_log", "PKB_AGENT_LOG");
        if (raw == null || raw.isBlank()) return false;
        if ("false".equalsIgnoreCase(raw) || "off".equalsIgnoreCase(raw)) return false;
        if ("true".equalsIgnoreCase(raw) || "all".equalsIgnoreCase(raw) || "on".equalsIgnoreCase(raw)) {
            return entityAllowed(entity);
        }
        for (String part : raw.split(",")) {
            if (part.trim().equalsIgnoreCase(entity)) return entityAllowed(entity);
        }
        return false;
    }

    public static boolean entityAllowed(String entity) {
        String raw = setting("pkb_agent_log_entity", "PKB_AGENT_LOG_ENTITY");
        if (raw == null || raw.isBlank() || "*".equals(raw) || "all".equalsIgnoreCase(raw)) return true;
        String token = entity == null || entity.isBlank() ? "-" : entity;
        for (String part : raw.split(",")) {
            if (part.trim().equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    public static int maxBytes() {
        String raw = setting("pkb_agent_log_max_bytes", "PKB_AGENT_LOG_MAX_BYTES");
        if (raw == null || raw.isBlank()) return DEFAULT_MAX_BYTES;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed < 64 ? 64 : parsed;
        } catch (NumberFormatException failure) {
            return DEFAULT_MAX_BYTES;
        }
    }

    public static void log(Path project, String entity, String message) {
        if (project == null || !enabled(entity)) return;
        String who = entity == null || entity.isBlank() ? "-" : entity;
        String line = Instant.now() + " " + who + " " + (message == null ? "" : message) + "\n";
        Path file = project.resolve(".pickleball").resolve("logs").resolve("agent-fast.log");
        try {
            Files.createDirectories(file.getParent());
            byte[] existing = Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0];
            byte[] addition = line.getBytes(StandardCharsets.UTF_8);
            byte[] combined = Arrays.copyOf(existing, existing.length + addition.length);
            System.arraycopy(addition, 0, combined, existing.length, addition.length);
            int max = maxBytes();
            if (combined.length > max) {
                int start = combined.length - max;
                int newline = start;
                while (newline < combined.length && combined[newline] != '\n') newline++;
                if (newline < combined.length - 1) start = newline + 1;
                combined = Arrays.copyOfRange(combined, start, combined.length);
            }
            Files.write(file, combined);
        } catch (IOException ignored) {
            // Logging never fails the verb.
        }
    }

    private static String setting(String property, String env) {
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) return value;
        return System.getenv(env);
    }
}
