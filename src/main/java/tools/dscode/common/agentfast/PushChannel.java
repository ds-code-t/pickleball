package tools.dscode.common.agentfast;

import tools.dscode.control.protocol.AgentEvents;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One append-only event stream per project ({@code .pickleball/agent-events.jsonl}).
 * The page binding writes a line as soon as the person sends, clicks, or answers.
 * {@code events --follow} reads that file. Mutations are debounced in the page.
 * This side caps the stream at {@link #RATE} lines a second and records the rest
 * as one {@code dropped} line.
 */
public final class PushChannel {
    public static final int RATE = 20;
    private static final Object LOCK = new Object();
    private static volatile Path bound = Path.of("");
    private static volatile boolean armed;
    private static long windowStart;
    private static int windowCount;
    private static int dropped;

    private PushChannel() {
    }

    public static void bind(Path project) {
        if (project != null) bound = project.toAbsolutePath().normalize();
        armed = project != null;
    }

    public static Path project() {
        return bound == null ? Path.of("") : bound;
    }

    static void resetForTests() {
        synchronized (LOCK) {
            windowStart = 0;
            windowCount = 0;
            dropped = 0;
        }
        bound = Path.of("");
        armed = false;
    }

    /** Stores one event on the bound project. A process with no live project drops it. */
    public static boolean accept(Map<String, String> event) {
        if (!armed) return false;
        return accept(project(), event);
    }

    public static boolean accept(Path project, Map<String, String> event) {
        if (event == null || event.isEmpty()) return false;
        Map<String, String> row = new LinkedHashMap<>();
        event.forEach((key, value) -> {
            if (key != null && value != null) row.put(key, StandardView.normalize(value));
        });
        if (!row.containsKey("type") && row.containsKey("event")) row.put("type", row.get("event"));
        if (!row.containsKey("event")) row.put("event", row.getOrDefault("type", "page"));
        row.putIfAbsent("type", row.get("event"));
        row.putIfAbsent("sessionId", "");
        row.putIfAbsent("at", Instant.now().toString());
        row.put("priority", StandardView.priorityName(row));
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (windowStart == 0 || now - windowStart >= 1000) {
                flushDropped(project, row.get("sessionId"));
                windowStart = now;
                windowCount = 0;
            }
            if (windowCount >= RATE) {
                dropped++;
                return false;
            }
            windowCount++;
        }
        AgentEvents.appendObject(project, row);
        return true;
    }

    private static void flushDropped(Path project, String sessionId) {
        if (dropped <= 0) return;
        Map<String, String> notice = new LinkedHashMap<>();
        notice.put("event", "dropped");
        notice.put("type", "dropped");
        notice.put("sessionId", sessionId == null ? "" : sessionId);
        notice.put("at", Instant.now().toString());
        notice.put("text", Integer.toString(dropped));
        notice.put("priority", "other");
        dropped = 0;
        AgentEvents.appendObject(project, notice);
    }

    public record Hit(int exit, Map<String, String> event, int next, List<Map<String, String>> matched) {
    }

    /**
     * Blocks until one stored line matches, or the timeout passes.
     * {@code timeoutMs} of 0 checks the file once. Own {@code source=agent} lines
     * are skipped unless {@code hideEcho} is false.
     */
    public static Hit follow(Path project, String until, String type, long timeoutMs, boolean hideEcho, int afterLine) {
        long wait = Math.max(0L, Math.min(timeoutMs, 120_000L));
        long deadline = System.nanoTime() + wait * 1_000_000L;
        int cursor = Math.max(0, afterLine);
        String wantType = type == null ? "" : type.trim();
        String wantUntil = until == null ? "" : StandardView.normalize(until);
        while (true) {
            List<Map<String, String>> rows = AgentEvents.lines(project);
            List<Map<String, String>> matched = new ArrayList<>();
            for (int index = cursor; index < rows.size(); index++) {
                cursor = index + 1;
                Map<String, String> row = rows.get(index);
                if (hideEcho && "agent".equalsIgnoreCase(row.get("source"))) continue;
                if (!wantType.isEmpty() && !wantType.equalsIgnoreCase(row.get("type")) && !wantType.equalsIgnoreCase(row.get("event"))) {
                    continue;
                }
                if (!matches(wantUntil, row)) continue;
                matched.add(row);
                return new Hit(0, row, cursor, matched);
            }
            if (System.nanoTime() >= deadline) return new Hit(1, Map.of(), cursor, matched);
            try {
                Thread.sleep(40);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Hit(1, Map.of(), cursor, matched);
            }
        }
    }

    static boolean matches(String until, Map<String, String> row) {
        if (until == null || until.isBlank()) return true;
        String token = until.toLowerCase(Locale.ROOT);
        if (token.startsWith("text=")) {
            String needle = token.substring("text=".length());
            String text = row.getOrDefault("text", "").toLowerCase(Locale.ROOT);
            return text.contains(needle);
        }
        return token.equalsIgnoreCase(row.get("type"))
                || token.equalsIgnoreCase(row.get("event"))
                || token.equalsIgnoreCase(row.get("source"))
                || token.equalsIgnoreCase(row.get("priority"));
    }
}
