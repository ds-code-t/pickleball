package tools.dscode.common.agentfast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** One JSON line per step so per-step latency stays on disk. */
public final class StepTiming {
    private StepTiming() {
    }

    public static void note(Path project, String id, long elapsedMs) {
        if (project == null) return;
        Path file = project.resolve(".pickleball").resolve("logs").resolve("step-timing.jsonl");
        String safe = id == null ? "" : id.replace("\\", "\\\\").replace("\"", "\\\"");
        String line = "{\"id\":\"" + safe + "\",\"elapsedMs\":" + Math.max(0L, elapsedMs) + "}\n";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Timing must not fail the step.
        }
    }
}
