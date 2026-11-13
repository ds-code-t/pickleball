package tools.dscode.common.agentsurface;

import tools.dscode.common.agentcontrol.PopupSurfaceBridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/** Toggleable popup log. WARNs go to stderr once per run and reason even when the log is off. */
public final class PopupLog {
    public static final int DEFAULT_MAX_BYTES = 1_048_576;
    private static final ConcurrentHashMap<String, Boolean> WARNED = new ConcurrentHashMap<>();

    private PopupLog() {
    }

    public static boolean enabled(String capability) {
        String raw = setting("pkb_agent_popup_log", "PKB_AGENT_POPUP_LOG");
        if (raw == null || raw.isBlank() || "false".equalsIgnoreCase(raw) || "off".equalsIgnoreCase(raw)) return false;
        if ("true".equalsIgnoreCase(raw) || "all".equalsIgnoreCase(raw) || "on".equalsIgnoreCase(raw)) return true;
        for (String part : raw.split(",")) {
            if (part.trim().equalsIgnoreCase(capability)) return true;
        }
        return false;
    }

    public static boolean entityAllowed(String entity) {
        String raw = setting("pkb_agent_popup_log_entity", "PKB_AGENT_POPUP_LOG_ENTITY");
        if (raw == null || raw.isBlank() || "*".equals(raw) || "all".equalsIgnoreCase(raw)) return true;
        String token = entity == null || entity.isBlank() ? "-" : entity;
        for (String part : raw.split(",")) {
            if (part.trim().equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    public static int maxBytes() {
        String raw = setting("pkb_agent_popup_log_max_bytes", "PKB_AGENT_POPUP_LOG_MAX_BYTES");
        if (raw == null || raw.isBlank()) return DEFAULT_MAX_BYTES;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(parsed, 64);
        } catch (NumberFormatException failure) {
            return DEFAULT_MAX_BYTES;
        }
    }

    public static void log(Path project, String runId, String capability, String entity, String message) {
        if (!enabled(capability) || !entityAllowed(entity)) return;
        String who = entity == null || entity.isBlank() ? "-" : entity;
        String line = Instant.now() + " " + capability + " " + who + " " + (message == null ? "" : message);
        Path file = popupDir(project, runId).resolve("popup.log");
        try {
            byte[] existing = Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0];
            byte[] addition = (line + "\n").getBytes(StandardCharsets.UTF_8);
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
            Files.createDirectories(file.getParent());
            Files.write(file, combined);
        } catch (IOException ignored) {
            // Logging must not fail a surface command.
        }
    }

    public static void warn(Path project, String runId, String reason, String message) {
        String key = (runId == null ? "" : runId) + "|" + reason;
        if (WARNED.putIfAbsent(key, Boolean.TRUE) == null) {
            System.err.println("WARN: agent popup " + reason + ": " + (message == null ? "" : message));
            remember(project, runId, reason, message);
        }
        log(project, runId, "lifecycle", runId, "WARN " + reason + " " + message);
    }

    public static java.util.List<String> warnings(Path project, String runId) {
        Path file = popupDir(project, runId).resolve("warnings.txt");
        if (!Files.isRegularFile(file)) return java.util.List.of();
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            return java.util.List.of();
        }
    }

    private static void remember(Path project, String runId, String reason, String message) {
        if (project == null || runId == null) return;
        try {
            Path file = popupDir(project, runId).resolve("warnings.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, reason + ": " + (message == null ? "" : message) + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // A warning must not fail the command that reported it.
        }
    }

    public static Path popupDir(Path project, String runId) {
        return PopupSurfaceBridge.agentRoot(project, runId).resolve("popup");
    }

    static String setting(String property, String env) {
        String propertyValue = System.getProperty(property);
        if (propertyValue != null && !propertyValue.isBlank()) return propertyValue;
        String envValue = System.getenv(env);
        return envValue == null || envValue.isBlank() ? null : envValue;
    }
}
