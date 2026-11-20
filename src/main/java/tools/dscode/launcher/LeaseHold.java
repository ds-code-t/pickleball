package tools.dscode.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Pause lease requested by {@code start --hold}. The marker
 * {@code .pickleball/workbench/pause-hold} is the same file the worker already
 * treats as a held lease. {@code hold.json} stores when it runs out.
 */
final class LeaseHold {
    private static final ObjectMapper JSON = new ObjectMapper();

    private LeaseHold() {
    }

    static void arm(Path project, String raw) throws java.io.IOException {
        if (project == null || raw == null || raw.isBlank()) return;
        long seconds = seconds(raw.trim());
        long now = System.currentTimeMillis();
        long expires = seconds < 0 ? Long.MAX_VALUE : now + seconds * 1000L;
        Path hold = project.resolve(".pickleball").resolve("workbench").resolve("pause-hold");
        Files.createDirectories(hold.getParent());
        Files.writeString(hold, raw.trim() + "\n");
        Path file = project.resolve(".pickleball").resolve("live-browser").resolve("hold.json");
        Files.createDirectories(file.getParent());
        ObjectNode node = JSON.createObjectNode();
        node.put("hold", raw.trim());
        node.put("leaseSeconds", seconds < 0 ? 3600 : seconds);
        node.put("forever", seconds < 0);
        node.put("armedAt", now);
        node.put("expires", expires);
        Files.writeString(file, JSON.writeValueAsString(node) + "\n");
    }

    /** Seconds still on the hold, {@code forever}, or {@code none}. */
    static String left(Path project) {
        if (project == null) return "none";
        Path file = project.resolve(".pickleball").resolve("live-browser").resolve("hold.json");
        if (!Files.isRegularFile(file)) {
            Path marker = project.resolve(".pickleball").resolve("workbench").resolve("pause-hold");
            return Files.isRegularFile(marker) ? "3600" : "none";
        }
        try {
            var node = JSON.readTree(file.toFile());
            if (node.path("forever").asBoolean(false)) return "forever";
            long expires = node.path("expires").asLong(0L);
            if (expires <= 0) return "none";
            long left = (expires - System.currentTimeMillis()) / 1000L;
            return left < 0 ? "0" : Long.toString(left);
        } catch (java.io.IOException failure) {
            return "none";
        }
    }

    /** Whole seconds. Negative means forever. */
    static long seconds(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return 3600;
        if ("forever".equals(text) || "hold".equals(text)) return -1;
        long mul = 1;
        String number = text;
        if (text.endsWith("ms")) return Math.max(1, Long.parseLong(text.substring(0, text.length() - 2).trim()) / 1000L);
        if (text.endsWith("s")) {
            number = text.substring(0, text.length() - 1);
        } else if (text.endsWith("m")) {
            number = text.substring(0, text.length() - 1);
            mul = 60;
        } else if (text.endsWith("h")) {
            number = text.substring(0, text.length() - 1);
            mul = 3600;
        } else if (text.endsWith("d")) {
            number = text.substring(0, text.length() - 1);
            mul = 86400;
        }
        return Math.max(1, Long.parseLong(number.trim()) * mul);
    }
}
