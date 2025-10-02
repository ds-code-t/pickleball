package tools.ds.modkit.state;

import io.cucumber.core.options.RuntimeOptions;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import static tools.ds.modkit.blackbox.BlackBoxBootstrap.*;
import static tools.ds.modkit.util.Reflect.findZeroArgMethod;
import static tools.ds.modkit.util.Reflect.nameOf;

public final class GlobalState {

    private static final GlobalState INSTANCE = new GlobalState();


    /**
     * JVM-wide store
     */
    private final Map<Object, Object> store = new ConcurrentHashMap<>();

    private GlobalState() {
    }

    /**
     * JVM-global accessor.
     */
    public static GlobalState getGlobalState() {
        return INSTANCE;
    }

    public static io.cucumber.core.runtime.Runtime getRuntime() {
        return (io.cucumber.core.runtime.Runtime) INSTANCE.byKey(K_RUNTIME);
    }

    public static io.cucumber.core.feature.FeatureParser getFeatureParser() {
        return (io.cucumber.core.feature.FeatureParser) INSTANCE.byKey(K_FEATUREPARSER);
    }

    public static io.cucumber.core.runtime.FeatureSupplier getFeatureSupplier() {
        return (io.cucumber.core.runtime.FeatureSupplier) INSTANCE.byKey(K_FEATURESUPPLIER);
    }
    /* ---------------- core registry ops (used by InstanceRegistry) ---------------- */

    /**
     * Register the same value under each provided key in the global store.
     */
    public void register(Object value, Object... keys) {
        if (value == null) return;
        if (keys == null || keys.length == 0) {
            store.put(value.getClass(), value);
            return;
        }
        for (Object k : keys) {
            if (k != null) store.put(k, value);
        }
    }

    /**
     * Remove a single key from the global store.
     */
    public void remove(Object key) {
        if (key == null) return;
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
        if (key == null || type == null) return null;
        Object v = store.get(key);
        return type.isInstance(v) ? type.cast(v) : null;
    }




    /* ---------------- tiny reflection helper ---------------- */


}
