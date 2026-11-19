package tools.dscode.common.agentfast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
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
        note(project, runId, chain, "", "", reason);
    }

    /**
     * One line when a chain actually changes step. {@code from} failed and {@code to} served the call.
     * The same run, chain, and reason is written once. The file is appended, not rewritten.
     */
    public static void note(Path project, String runId, String chain, String from, String to, String reason) {
        if (project == null || !enabled()) return;
        String safeChain = chain == null ? "" : chain;
        String safeReason = reason == null ? "" : reason;
        String safeRun = runId == null ? "" : runId;
        String safeFrom = from == null ? "" : from;
        String safeTo = to == null ? "" : to;
        if (!SEEN.add(safeRun + "|" + safeChain + "|" + safeReason)) return;
        Path file = project.resolve(".pickleball").resolve("logs").resolve("fallback-chain.jsonl");
        StringBuilder line = new StringBuilder();
        line.append("{\"run\":\"").append(escape(safeRun)).append("\",\"chain\":\"").append(escape(safeChain)).append('"');
        if (!safeFrom.isBlank()) line.append(",\"from\":\"").append(escape(safeFrom)).append('"');
        if (!safeTo.isBlank()) line.append(",\"to\":\"").append(escape(safeTo)).append('"');
        line.append(",\"reason\":\"").append(escape(safeReason)).append("\",\"at\":\"").append(Instant.now()).append("\"}\n");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (Files.size(file) > DEFAULT_MAX_BYTES) trim(file);
        } catch (IOException ignored) {
            // A fallback note must not fail the verb.
        }
    }

    /** Drops remembered reasons when the long-lived host starts a different run. */
    public static void beginRun(String runId) {
        String safe = runId == null ? "" : runId;
        if (safe.equals(CURRENT_RUN)) return;
        CURRENT_RUN = safe;
        SEEN.clear();
    }

    public static void resetForTests() {
        SEEN.clear();
        CURRENT_RUN = "";
    }

    private static volatile String CURRENT_RUN = "";

    private static void trim(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length <= DEFAULT_MAX_BYTES) return;
        int start = bytes.length - DEFAULT_MAX_BYTES;
        while (start < bytes.length && bytes[start] != '\n') start++;
        if (start < bytes.length - 1) start++;
        Files.write(file, java.util.Arrays.copyOfRange(bytes, start, bytes.length));
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
