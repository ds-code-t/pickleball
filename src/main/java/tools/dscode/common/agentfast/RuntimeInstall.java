package tools.dscode.common.agentfast;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which driver already received {@code pkb-runtime.js}.
 * A later call omits those bytes. {@link #forget} forces the next call to send them again.
 */
public final class RuntimeInstall {
    private static final Set<Integer> INSTALLED = ConcurrentHashMap.newKeySet();

    private RuntimeInstall() {
    }

    public static String function(int driverKey, boolean install, String runtime, String call) {
        boolean include = install && INSTALLED.add(driverKey);
        String prefix = include && runtime != null ? runtime + "\n;" : "";
        return "function(raw){" + prefix + (call == null ? "" : call) + "}";
    }

    /** True when this driver has not been given the runtime yet. The caller sends the bytes. */
    public static boolean consume(int driverKey) {
        return INSTALLED.add(driverKey);
    }

    public static void forget(int driverKey) {
        INSTALLED.remove(driverKey);
    }

    public static void reset() {
        INSTALLED.clear();
    }
}
