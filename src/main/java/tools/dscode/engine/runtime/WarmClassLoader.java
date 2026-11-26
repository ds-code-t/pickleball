package tools.dscode.engine.runtime;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Parent-last only for ingested glue packages. {@code tools.dscode}, {@code dev.domengine},
 * and {@code io.cucumber} are always parent-first. Not stored in a {@code ThreadLocal}.
 */
public class WarmClassLoader extends URLClassLoader {
    private static final String[] PARENT_FIRST = {"tools.dscode", "dev.domengine", "io.cucumber"};

    private final Set<String> parentLast;
    private volatile boolean closed;

    public WarmClassLoader(URL[] urls, ClassLoader parent, Collection<String> parentLastPackages) {
        super(urls == null ? new URL[0] : urls.clone(), parent == null ? spineLoader() : parent);
        this.parentLast = normalize(parentLastPackages);
    }

    public static ClassLoader spineLoader() {
        ClassLoader loader = WarmClassLoader.class.getClassLoader();
        return loader == null ? ClassLoader.getSystemClassLoader() : loader;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        super.close();
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return loadClass(name, false);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (closed) throw new IllegalStateException("warm class loader is closed");
        synchronized (getClassLoadingLock(name)) {
            if (closed) throw new IllegalStateException("warm class loader is closed");
            Class<?> found = findLoadedClass(name);
            if (found == null && parentLast(name)) {
                try {
                    found = findClass(name);
                } catch (ClassNotFoundException missing) {
                    found = null;
                }
            }
            if (found == null) {
                ClassLoader parent = getParent();
                if (parent != null) {
                    try {
                        found = parent.loadClass(name);
                    } catch (ClassNotFoundException missing) {
                        found = null;
                    }
                }
            }
            if (found == null) found = findClass(name);
            if (resolve) resolveClass(found);
            return found;
        }
    }

    boolean parentLast(String name) {
        if (name == null || name.isBlank()) return false;
        for (String prefix : PARENT_FIRST) {
            if (name.equals(prefix) || name.startsWith(prefix + ".")) return false;
        }
        for (String pkg : parentLast) {
            if (name.equals(pkg) || name.startsWith(pkg + ".")) return true;
        }
        return false;
    }

    private static Set<String> normalize(Collection<String> packages) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (packages == null) return Set.of();
        for (String pkg : packages) {
            if (pkg == null) continue;
            String trimmed = pkg.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
        return Set.copyOf(names);
    }
}
