package tools.dscode.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

/**
 * Toggleable, per-capability, per-entity, date-stamped, size-capped engine log.
 * Off unless {@code pkb_engine_log} (or {@code PKB_ENGINE_LOG}) names
 * {@code true}, {@code all}, or a comma-separated capability list.
 * {@code pkb_engine_log_entity} limits lines to those entities when set.
 * The file is {@code .pickleball/engine/engine.log}.
 */
public final class EngineLog {
    public static final int DEFAULT_MAX_BYTES = 1_048_576;

    private EngineLog() {
    }

    public static boolean enabled(String capability) {
        String raw = setting("pkb_engine_log", "PKB_ENGINE_LOG");
        if (raw == null || raw.isBlank()) return false;
        if ("false".equalsIgnoreCase(raw) || "off".equalsIgnoreCase(raw)) return false;
        if ("true".equalsIgnoreCase(raw) || "all".equalsIgnoreCase(raw) || "on".equalsIgnoreCase(raw)) {
            return true;
        }
        for (String part : raw.split(",")) {
            if (part.trim().equalsIgnoreCase(capability)) return true;
        }
        return false;
    }

    public static boolean entityAllowed(String entity) {
        String raw = setting("pkb_engine_log_entity", "PKB_ENGINE_LOG_ENTITY");
        if (raw == null || raw.isBlank() || "*".equals(raw) || "all".equalsIgnoreCase(raw)) return true;
        String token = entity == null || entity.isBlank() ? "-" : entity;
        for (String part : raw.split(",")) {
            if (part.trim().equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    public static int maxBytes() {
        String raw = setting("pkb_engine_log_max_bytes", "PKB_ENGINE_LOG_MAX_BYTES");
        if (raw == null || raw.isBlank()) return DEFAULT_MAX_BYTES;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed < 64 ? 64 : parsed;
        } catch (NumberFormatException failure) {
            return DEFAULT_MAX_BYTES;
        }
    }

    public static void log(Path project, String capability, String entity, String message) {
        if (project == null || capability == null || !enabled(capability) || !entityAllowed(entity)) return;
        String who = entity == null || entity.isBlank() ? "-" : entity;
        String line = Instant.now() + " " + capability + " " + who + " " + (message == null ? "" : message) + "\n";
        Path file = project.resolve(".pickleball").resolve("engine").resolve("engine.log");
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
            // Logging must not fail a run.
        }
    }

    private static String setting(String property, String env) {
        String propertyValue = System.getProperty(property);
        if (propertyValue != null && !propertyValue.isBlank()) return propertyValue;
        return System.getenv(env);
    }
}
