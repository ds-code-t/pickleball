// src/main/java/tools/dscode/registry/GlobalRegistry.java
package tools.dscode.registry;

import org.openqa.selenium.WebDriver;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GlobalRegistry {

    static {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("### UNCAUGHT in thread " + t.getName());
            e.printStackTrace();
        });
    }

    private GlobalRegistry() {
    }

    public static final ConcurrentHashMap<String, Object> GLOBAL = new ConcurrentHashMap<>();
    public static final ThreadLocal<ConcurrentHashMap<String, Object>> LOCAL =
            ThreadLocal.withInitial(ConcurrentHashMap::new);

    public static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT);
    }

    public static String keyFor(Class<?> type) {
        return normalizeKey(type.getName());
    }

    public static void registerGlobal(Object instance) {
        GLOBAL.putIfAbsent(keyFor(instance.getClass()), instance);
        // DEBUG (optional):
        // System.out.println("[Reg][GLOBAL] " + instance.getClass().getName());
    }

    public static void registerLocal(Object instance) {
        LOCAL.get().putIfAbsent(keyFor(instance.getClass()), instance);
        // System.out.println("[Reg][LOCAL ] " + instance.getClass().getName());
    }

    public static void registerBoth(Object instance) {
        registerGlobal(instance);
        registerLocal(instance);
    }

    public static void putLocal(String key, Object value) {
        LOCAL.get().put(normalizeKey(key), value);
    }

    public static <T> T getLocal(String key) {
        return (T) LOCAL.get().get(normalizeKey(key));
    }

    public static void putGlobal(String key, Object value) {
        GLOBAL.put(normalizeKey(key), value);
    }

    public static <T> T getGlobal(String key) {
        return (T) GLOBAL.get(normalizeKey(key));
    }


    // Exact-name lookups (what you already had)
    @SuppressWarnings("unchecked")
    public static <T> T globalOf(String fqcn) {
        return (T) GLOBAL.get(normalizeKey(fqcn));
    }

    @SuppressWarnings("unchecked")
    public static <T> T localOf(String fqcn) {
        return (T) LOCAL.get().get(normalizeKey(fqcn));
    }

    @SuppressWarnings("unchecked")
    public static <T> T localOrGlobalOf(String fqcn) {
        var key = normalizeKey(fqcn);
        Object inst = LOCAL.get().get(key);
        if (inst == null) inst = GLOBAL.get(key);
        return (T) inst;
    }

    // Class-exact lookups
    public static <T> T globalOf(Class<T> type) {
        return type.cast(globalOf(type.getName()));
    }

    public static <T> T localOf(Class<T> type) {
        return type.cast(localOf(type.getName()));
    }

    public static <T> T localOrGlobalOf(Class<T> type) {
        return type.cast(localOrGlobalOf(type.getName()));
    }

    // 🔹 NEW: assignable lookups (handles interfaces/abstracts vs concrete impls)
    public static <T> T globalAssignableOf(Class<T> type) {
        for (Map.Entry<String, Object> e : GLOBAL.entrySet()) {
            Object v = e.getValue();
            if (type.isInstance(v)) return type.cast(v);
        }
        return null;
    }

    public static <T> T localAssignableOf(Class<T> type) {
        for (Map.Entry<String, Object> e : LOCAL.get().entrySet()) {
            Object v = e.getValue();
            if (type.isInstance(v)) return type.cast(v);
        }
        return null;
    }

    public static <T> T localOrGlobalAssignableOf(Class<T> type) {
        T v = localAssignableOf(type);
        return (v != null) ? v : globalAssignableOf(type);
    }

    public static void clearLocal() {
        LOCAL.get().clear();
    }

    public static void removeLocal() {
        LOCAL.remove();
    }

    public static List<WebDriver> getScenarioWebDrivers() {
        return LOCAL.get().values().stream().filter(WebDriver.class::isInstance).map(WebDriver.class::cast).toList();
    }

}
