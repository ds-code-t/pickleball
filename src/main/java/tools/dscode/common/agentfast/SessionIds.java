package tools.dscode.common.agentfast;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Short ids for one controller JVM. {@code s1.h42} follows the DOM node
 * (the backend id), not the document version. The long {@code ref:} stays
 * the expansion.
 */
public final class SessionIds {
    private static final ConcurrentHashMap<String, String> SCOPE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> NODE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> FULL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicInteger> COUNTERS = new ConcurrentHashMap<>();
    private static final AtomicInteger SESSIONS = new AtomicInteger();

    private SessionIds() {
    }

    public static String scope(String runId) {
        String key = runId == null || runId.isBlank() ? "session" : runId;
        return SCOPE.computeIfAbsent(key, ignored -> "s" + SESSIONS.incrementAndGet());
    }

    /** The run id a short id ({@code s2.h3}) was given to, or null when its scope is not known here. */
    public static String ownerOf(String shortId) {
        if (shortId == null) return null;
        String token = shortId.trim();
        int dot = token.indexOf(".h");
        if (dot <= 0) return null;
        String prefix = token.substring(0, dot);
        for (Map.Entry<String, String> entry : SCOPE.entrySet()) {
            if (prefix.equals(entry.getValue())) return entry.getKey();
        }
        return null;
    }

    /**
     * HCR-13: short ids are scoped per run id. A blank string when {@code shortId} is this run's or unknown here,
     * otherwise a warning naming the run id it belongs to.
     */
    public static String foreignWarning(String shortId, String runId) {
        if (shortId == null || !shortId.trim().matches("s\\d+\\.h\\d+")) return "";
        String owner = ownerOf(shortId);
        if (owner == null) return "";
        String key = runId == null || runId.isBlank() ? "session" : runId;
        if (owner.equals(key)) return "";
        return "short id " + shortId.trim() + " belongs to run id " + owner + ", not " + key
                + "; short ids are scoped per run id, so find the element again with this --run-id";
    }

    /** Same backend id keeps the same short id. The stored expansion updates. */
    public static String intern(String runId, long backend, String fullRef) {
        String scope = scope(runId);
        String nodeKey = scope + ":" + backend;
        String id = NODE.computeIfAbsent(nodeKey, ignored -> {
            int next = COUNTERS.computeIfAbsent(scope, key -> new AtomicInteger()).incrementAndGet();
            return scope + ".h" + next;
        });
        if (fullRef != null && !fullRef.isBlank()) FULL.put(id, fullRef);
        return id;
    }

    /** Full {@code ref:} for a short id. A value that is already a {@code ref:} is returned as-is. Null when unknown. */
    public static String expand(String id) {
        if (id == null || id.isBlank()) return null;
        String token = id.trim();
        if (token.startsWith("ref:")) return token;
        return FULL.get(token);
    }

    /** Rewrites short ids in {@code target}, {@code ref}, and {@code within} to their full refs. */
    public static Map<String, String> rewrite(Map<String, String> args) {
        if (args == null || args.isEmpty()) return args == null ? Map.of() : args;
        Map<String, String> copy = null;
        for (String key : new String[]{"target", "ref", "within"}) {
            String value = args.get(key);
            String full = expand(value);
            if (full == null || full.equals(value)) continue;
            if (copy == null) copy = new LinkedHashMap<>(args);
            copy.put(key, full);
        }
        return copy == null ? args : copy;
    }
}
