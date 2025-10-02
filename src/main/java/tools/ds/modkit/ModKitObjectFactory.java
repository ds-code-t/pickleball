package tools.ds.modkit;

import io.cucumber.core.backend.ObjectFactory;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

public final class ModKitObjectFactory implements ObjectFactory {

    // Attach agent as early as the class is loaded by ServiceLoader
    static {
        System.out.println("@@static block of ModKitObjectFactory");
        try {
            EnsureInstalled.ensureOrDie(); // your one-stop bootstrapper
            System.err.println("[modkit] ModKitObjectFactory: agent attached");
        } catch (RuntimeException re) {
            throw re; // EnsureInstalled already prints helpful guidance
        } catch (Throwable t) {
            throw new IllegalStateException("ModKit bootstrap failed", t);
        }
    }

    // Plain, default-like ObjectFactory implementation
    private final Map<Class<?>, Object> instances = new HashMap<>();

    @Override
    public boolean addClass(Class<?> glueClass) {
        // Accept all glue; instances are created lazily
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getInstance(Class<T> glueClass) {
        return (T) instances.computeIfAbsent(glueClass, ModKitObjectFactory::newInstance);
    }

    @Override
    public void start() {
        // No-op: all boot work is done in the static block
    }

    @Override
    public void stop() {
        instances.clear();
    }

    // Utility: construct with no-arg ctor
    private static Object newInstance(Class<?> type) {
        try {
            Constructor<?> ctor = type.getDeclaredConstructor();
            if (!ctor.canAccess(null)) ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Glue class requires a no-arg constructor: " + type.getName(), e);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to instantiate glue: " + type.getName(), t);
        }
    }
}
