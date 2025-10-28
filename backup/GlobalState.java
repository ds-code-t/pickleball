package io.cucumber.core.runner;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GlobalState {
    // private static volatile boolean initialized = false;
    // private static final Object lock = new Object();

    private GlobalState() {

    }

    // public static void globalInitialize() {
    // if (initialized)
    // return; // fast path
    // Pickle pickle =
    // localOrGlobalOf("io.cucumber.core.gherkin.messages.GherkinMessagesPickle");
    // synchronized (lock) {
    // if (initialized)
    // return; // re-check inside lock
    // globalDialect = GherkinDialects.getDialect(pickle.getLanguage())
    // .orElse(GherkinDialects.getDialect("en").get());
    // givenKeyword = globalDialect.getGivenKeywords().getFirst();
    // initialized = true;
    // }
    // }

    // public static GherkinDialect globalDialect;
    // public static String givenKeyword;

    private static final GlobalState INSTANCE = new GlobalState();

    /**
     * JVM-wide store
     */
    private final Map<Object, Object> store = new ConcurrentHashMap<>();

    /**
     * JVM-global accessor.
     */
    public static GlobalState getGlobalState() {
        return INSTANCE;
    }

    /*
     * ---------------- core registry ops (used by InstanceRegistry)
     * ----------------
     */

    /**
     * Register the same value under each provided key in the global store.
     */
    public void register(Object value, Object... keys) {
        if (value == null)
            return;
        if (keys == null || keys.length == 0) {
            store.put(value.getClass(), value);
            return;
        }
        for (Object k : keys) {
            if (k != null)
                store.put(k, value);
        }
    }

    /**
     * Remove a single key from the global store.
     */
    public void remove(Object key) {
        if (key == null)
            return;
        store.remove(key);
    }

    /**
     * Clear the global store (e.g., between full runs).
     */
    public void clear() {
        store.clear();
    }

    /* ---------------- convenience accessors ---------------- */

    /**
     * Get any registered value by key (or null).
     */
    public Object byKey(Object key) {
        return (key == null) ? null : store.get(key);
    }

    /**
     * Get and cast by key (returns null if missing or wrong type).
     */
    public <T> T byKey(Object key, Class<T> type) {
        if (key == null || type == null)
            return null;
        Object v = store.get(key);
        return type.isInstance(v) ? type.cast(v) : null;
    }

    /* ---------------- tiny reflection helper ---------------- */

}
