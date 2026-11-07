package tools.dscode.control.protocol;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Scenario and playback completion hooks shared by the controller and the
 * Workbench. The Workbench does not compile against the Pickleball runtime,
 * so native-overlay removal registers here.
 */
public final class AgentSurfaceEnd {
    private static final CopyOnWriteArrayList<Runnable> HOOKS = new CopyOnWriteArrayList<>();

    private AgentSurfaceEnd() {
    }

    public static void register(Runnable hook) {
        if (hook != null && !HOOKS.contains(hook)) HOOKS.add(hook);
    }

    public static void complete() {
        for (Runnable hook : List.copyOf(HOOKS)) {
            try {
                hook.run();
            } catch (RuntimeException ignored) {
                // One surface must not block the rest of scenario cleanup.
            }
        }
    }
}
