package tools.dscode.common.agentfast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

/** One JSON line per step so per-step latency stays on disk. On unless {@code pkb_step_timing=off}. */
public final class StepTiming {
    static final int DEFAULT_MAX_BYTES = 1_048_576;

    private StepTiming() {
    }

    public static void note(Path project, String id, long elapsedMs) {
        if (project == null || !enabled()) return;
        Path file = project.resolve(".pickleball").resolve("logs").resolve("step-timing.jsonl");
        String safe = id == null ? "" : id.replace("\\", "\\\\").replace("\"", "\\\"");
        String line = "{\"id\":\"" + safe + "\",\"elapsedMs\":" + Math.max(0L, elapsedMs)
                + ",\"at\":\"" + Instant.now() + "\"}\n";
        try {
            Files.createDirectories(file.getParent());
            byte[] existing = Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0];
            byte[] addition = line.getBytes(StandardCharsets.UTF_8);
            byte[] combined = Arrays.copyOf(existing, existing.length + addition.length);
            System.arraycopy(addition, 0, combined, existing.length, addition.length);
            int max = DEFAULT_MAX_BYTES;
            if (combined.length > max) {
                int start = combined.length - max;
                int newline = start;
                while (newline < combined.length && combined[newline] != '\n') newline++;
                if (newline < combined.length - 1) start = newline + 1;
                combined = Arrays.copyOfRange(combined, start, combined.length);
            }
            Files.write(file, combined);
        } catch (IOException ignored) {
            // Timing must not fail the step.
        }
    }

    private static boolean enabled() {
        String raw = System.getProperty("pkb_step_timing");
        if (raw == null || raw.isBlank()) raw = System.getenv("PKB_STEP_TIMING");
        if (raw == null || raw.isBlank()) return true;
        return !"off".equalsIgnoreCase(raw.trim()) && !"false".equalsIgnoreCase(raw.trim());
    }
}
