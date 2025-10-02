package tools.ds.modkit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Programmatic plugin hook for consumers who don't want to use ServiceLoader.
 * Call {@link #onInstall(Runnable)} from consumer code to register a callback
 * that will run once during ModKit installation (before weaving/retransform).
 */
public final class ModKitPlugins {
    private static final List<Runnable> HOOKS = new CopyOnWriteArrayList<>();

    /** Register a callback to run during ModKit install. Safe to call multiple times / from any thread. */
    public static void onInstall(Runnable r) {
        if (r != null) HOOKS.add(r);
    }

    /** Internal: executed by ModKitCore during install. */
    static void fire() {
        for (Runnable r : HOOKS) {
            try { r.run(); } catch (Throwable t) {
                System.err.println("[modkit] plugin hook failed: " + t);
            }
        }
    }

    private ModKitPlugins() {}
}
