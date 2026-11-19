package tools.dscode.common.agentfast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One JSON line per run and reason for the locate, act, wait, and snapshot chains.
 * On unless {@code pkb_fallback_log=off}. Independent of {@code pkb_composite_fallback_log}.
 */
public final class FallbackLog {
    static final int DEFAULT_MAX_BYTES = 1_048_576;
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private FallbackLog() {
    }

    public static void note(Path project, String runId, String chain, String reason) {
        if (project == null || !enabled()) return;
        String safeChain = chain == null ? "" : chain;
        String safeReason = reason == null ? "" : reason;
        String safeRun = runId == null ? "" : runId;
        if (!SEEN.add(safeRun + "|" + safeChain + "|" + safeReason)) return;
        Path file = project.resolve(".pickleball").resolve("logs").resolve("fallback-chain.jsonl");
        String line = "{\"run\":\"" + escape(safeRun) + "\",\"chain\":\"" + escape(safeChain)
                + "\",\"reason\":\"" + escape(safeReason) + "\",\"at\":\"" + Instant.now() + "\"}\n";
        try {
            Files.createDirectories(file.getParent());
            byte[] existing = Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0];
            byte[] addition = line.getBytes(StandardCharsets.UTF_8);
            byte[] combined = Arrays.copyOf(existing, existing.length + addition.length);
            System.arraycopy(addition, 0, combined, existing.length, addition.length);
            if (combined.length > DEFAULT_MAX_BYTES) {
                int start = combined.length - DEFAULT_MAX_BYTES;
                int newline = start;
                while (newline < combined.length && combined[newline] != '\n') newline++;
                if (newline < combined.length - 1) start = newline + 1;
                combined = Arrays.copyOfRange(combined, start, combined.length);
            }
            Files.write(file, combined);
        } catch (IOException ignored) {
            // A fallback note must not fail the verb.
        }
    }

    public static void resetForTests() {
        SEEN.clear();
    }

    private static boolean enabled() {
        String raw = System.getProperty("pkb_fallback_log");
        if (raw == null || raw.isBlank()) raw = System.getenv("PKB_FALLBACK_LOG");
        if (raw == null || raw.isBlank()) return true;
        return !"off".equalsIgnoreCase(raw.trim()) && !"false".equalsIgnoreCase(raw.trim());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
