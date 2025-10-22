package tools.dscode.common;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public abstract class SelfRegistering {

    /** Global registry: key is lower-cased fully-qualified class name. */
    public static final ConcurrentHashMap<String, SelfRegistering> GLOBAL = new ConcurrentHashMap<>();

    /** Per-thread registry: key is lower-cased fully-qualified class name. */
    public static final ThreadLocal<ConcurrentHashMap<String, SelfRegistering>> LOCAL = ThreadLocal
            .withInitial(ConcurrentHashMap::new);

    /**
     * No-arg ctor: registers the instance under its concrete runtime class name
     * (fully-qualified, lower-cased) in both registries. Note: this escapes
     * `this` intentionally; do not call overridable methods.
     */
    protected SelfRegistering() {
        String key = keyFor(getClass());
        GLOBAL.putIfAbsent(key, this); // first-wins; use put(...) for last-wins
        LOCAL.get().putIfAbsent(key, this);
    }

    /**
     * Normalize an arbitrary string key to our case-insensitive canonical form.
     */
    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT);
    }

    /** Build the canonical key for a Class. */
    private static String keyFor(Class<?> type) {
        // Fully-qualified name, lower-cased with ROOT locale for stability
        return normalizeKey(type.getName());
    }

    /** Retrieve from global by Class. */
    public static <T extends SelfRegistering> T globalOf(Class<T> type) {
        SelfRegistering inst = GLOBAL.get(keyFor(type));
        return (inst == null) ? null : type.cast(inst);
    }

    /** Retrieve from local (thread) by Class. */
    public static <T extends SelfRegistering> T localOf(Class<T> type) {
        SelfRegistering inst = LOCAL.get().get(keyFor(type));
        return (inst == null) ? null : type.cast(inst);
    }

    /**
     * Retrieve from local first, else global, by Class. Returns null if not
     * found in either.
     */
    public static <T extends SelfRegistering> T localOrGlobalOf(Class<T> type) {
        String key = keyFor(type);
        SelfRegistering inst = LOCAL.get().get(key);
        if (inst == null)
            inst = GLOBAL.get(key);
        return (inst == null) ? null : type.cast(inst);
    }

    /**
     * Retrieve from global by fully-qualified class name (case-insensitive).
     */
    @SuppressWarnings("unchecked")
    public static <T extends SelfRegistering> T globalOf(String fqcn) {
        return (T) GLOBAL.get(normalizeKey(fqcn));
    }

    /** Retrieve from local by fully-qualified class name (case-insensitive). */
    @SuppressWarnings("unchecked")
    public static <T extends SelfRegistering> T localOf(String fqcn) {
        return (T) LOCAL.get().get(normalizeKey(fqcn));
    }

    /** Retrieve from local first, else global, by FQCN (case-insensitive). */
    @SuppressWarnings("unchecked")
    public static <T extends SelfRegistering> T localOrGlobalOf(String fqcn) {
        String key = normalizeKey(fqcn);
        SelfRegistering inst = LOCAL.get().get(key);
        if (inst == null)
            inst = GLOBAL.get(key);
        return (T) inst;
    }

    /** Clear the thread-local registry for this thread. */
    public static void clearLocal() {
        LOCAL.get().clear();
    }

    /** Remove the entire thread-local map (frees it for this thread). */
    public static void removeLocal() {
        LOCAL.remove();
    }
}
