package tools.dscode.common.agentfast;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Remembers which driver already received {@code pkb-runtime.js} for the current document.
 * A later call on that document omits those bytes. A new document is covered by the
 * {@code pkb-surface} preload; {@link #forget} forces the next call to send the bytes again
 * when the page reports the runtime missing.
 */
public final class RuntimeInstall {
    private static final Set<Integer> INSTALLED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger INCLUSIONS = new AtomicInteger();

    private RuntimeInstall() {
    }

    /** How many times the runtime bytes were actually included. */
    public static int inclusions() {
        return INCLUSIONS.get();
    }

    public static String function(int driverKey, boolean install, String runtime, String call) {
        boolean include = install && INSTALLED.add(driverKey);
        if (include && runtime != null) INCLUSIONS.incrementAndGet();
        String prefix = include && runtime != null ? runtime + "\n;" : "";
        return "function(raw){" + prefix + (call == null ? "" : call) + "}";
    }

    /** True when this driver has not been given the runtime yet. The caller sends the bytes. */
    public static boolean consume(int driverKey) {
        boolean added = INSTALLED.add(driverKey);
        if (added) INCLUSIONS.incrementAndGet();
        return added;
    }

    public static void forget(int driverKey) {
        INSTALLED.remove(driverKey);
    }

    public static void reset() {
        INSTALLED.clear();
        INCLUSIONS.set(0);
    }
}
