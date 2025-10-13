package tools.ds.modkit.trace;

import tools.ds.modkit.state.GlobalState;
import tools.ds.modkit.state.ScenarioState;

import java.util.Optional;

/** Facade that delegates to ScenarioState (thread) and GlobalState (JVM). */
public final class InstanceRegistry {
    private InstanceRegistry() {}

    /* ------------ helpers ------------ */

    private static ScenarioState cur() {
        return ScenarioState.getScenarioState(); // may be null if beginNew()/set() wasn't called
    }

    /* ------------ Per-thread ------------ */

    /** Register value under each key in the current thread’s ScenarioState (no-op if none). */
    public static void register(Object value, Object... keys) {
        ScenarioState s = cur();

        if (s != null) s.register(value, keys);
    }

    /** Get untyped value from the current thread’s ScenarioState (null if none or missing). */
    public static Object get(Object key) {
        ScenarioState s = cur();
        return (s == null) ? null : s.getInstance(key);
    }

    /** Typed get from the current thread’s ScenarioState. */
    public static <T> Optional<T> get(Object key, Class<T> type) {
        ScenarioState s = cur();
        return Optional.ofNullable(s == null ? null : s.getInstance(key, type));
    }

    /** Remove a key from the current thread’s ScenarioState (no-op if none). */
    public static void remove(Object key) {
        ScenarioState s = cur();
        if (s != null) s.remove(key);
    }

    /** Clear the current thread’s ScenarioState store (no-op if none). */
    public static void clear() {
        ScenarioState s = cur();
        if (s != null) s.clear();
    }

    /* ------------ Global ------------ */

    public static void globalRegister(Object value, Object... keys) {
        GlobalState.getGlobalState().register(value, keys);
    }

    public static Object globalGet(Object key) {
        return GlobalState.getGlobalState().byKey(key);
    }

    public static <T> Optional<T> globalGet(Object key, Class<T> type) {
        return Optional.ofNullable(GlobalState.getGlobalState().byKey(key, type));
    }

    public static void globalRemove(Object key) {
        GlobalState.getGlobalState().remove(key);
    }

    public static void globalClear() {
        GlobalState.getGlobalState().clear();
    }
}
