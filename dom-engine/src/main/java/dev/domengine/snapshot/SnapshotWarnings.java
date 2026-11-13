package dev.domengine.snapshot;

import dev.domengine.log.EngineLog;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One WARN per run and reason. Callers pass a stable reason such as
 * {@code selenium reason=no-adapter} or {@code robot reason=native-tool-missing}.
 */
public final class SnapshotWarnings {
    private static final Set<String> FIRED = ConcurrentHashMap.newKeySet();

    private SnapshotWarnings() {
    }

    public static void resetForTests() {
        FIRED.clear();
    }

    /** @return true only the first time this reason is seen */
    public static boolean once(String reason) {
        String key = reason == null ? "" : reason;
        if (!FIRED.add(key)) return false;
        EngineLog.warn("snapshot", "snapshot fallback " + key);
        return true;
    }
}
