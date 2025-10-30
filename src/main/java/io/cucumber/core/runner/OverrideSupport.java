package io.cucumber.core.runner;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plain Java helper: store and retrieve per-instance override values.
 * No AspectJ types referenced here. Safe with javac.
 */
public final class OverrideSupport {
    private OverrideSupport() {}

    // Weak keys prevent leaks when target objects are GC'd.
    private static final Map<Object, Map<String, Object>> STORE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static Map<String, Object> mapFor(Object target) {
        if (target == null) throw new NullPointerException("target");
        synchronized (STORE) {
            return STORE.computeIfAbsent(target, k -> new ConcurrentHashMap<>());
        }
    }

    /** Set/replace an override value for a property name on the given target. */
    public static void set(Object target, String property, Object value) {
        mapFor(target).put(property, value);
    }

    /** Remove all overrides for the given target. */
    public static void clear(Object target) {
        mapFor(target).clear();
    }

    /** Internal access for the aspect. */
    static Map<String, Object> getMap(Object target) {
        return mapFor(target);
    }
}
