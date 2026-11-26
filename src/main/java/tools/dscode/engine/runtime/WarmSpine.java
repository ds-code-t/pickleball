package tools.dscode.engine.runtime;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * One {@link WarmClassLoader} per snapshot id. Replacing a loader closes the old one
 * and removes that map entry. The loader is not stored in a {@code ThreadLocal}.
 */
public final class WarmSpine {
    private static final WarmSpine SHARED = new WarmSpine();

    private final ConcurrentHashMap<String, WarmClassLoader> loaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Process> children = new ConcurrentHashMap<>();

    public WarmSpine() {
    }

    public static WarmSpine shared() {
        return SHARED;
    }

    public WarmClassLoader loader(String snapshotId, URL[] urls, Collection<String> packages) {
        String key = key(snapshotId);
        WarmClassLoader existing = loaders.get(key);
        if (existing != null && !existing.isClosed()) return existing;
        WarmClassLoader created = new WarmClassLoader(urls, WarmClassLoader.spineLoader(), packages);
        WarmClassLoader previous = loaders.put(key, created);
        if (previous != null && previous != created) closeQuietly(previous);
        return created;
    }

    /** Closes the previous loader for this snapshot and drops that map entry. */
    public WarmClassLoader replace(String snapshotId, WarmClassLoader next) {
        String key = key(snapshotId);
        if (next == null) throw new IllegalArgumentException("warm loader is required");
        WarmClassLoader previous = loaders.put(key, next);
        if (previous != null && previous != next) closeQuietly(previous);
        return next;
    }

    /** Drops the entry when it is still {@code loader}. Does not close it. */
    public void detach(String snapshotId, WarmClassLoader loader) {
        if (loader == null) return;
        loaders.remove(key(snapshotId), loader);
    }

    public WarmClassLoader get(String snapshotId) {
        return loaders.get(key(snapshotId));
    }

    public void reset() {
        stopChildren();
        for (WarmClassLoader loader : loaders.values()) closeQuietly(loader);
        loaders.clear();
    }

    /** Live subprocess JVMs. A process that has already exited is dropped. */
    public int liveChildren() {
        children.entrySet().removeIf(entry -> entry.getValue() == null || !entry.getValue().isAlive());
        return children.size();
    }

    public Process track(Process process) {
        if (process == null) throw new IllegalArgumentException("subprocess is required");
        children.put(process.pid(), process);
        return process;
    }

    /** Stops every tracked child. `spine stop` calls this. The pool is then zero. */
    public void stopChildren() {
        for (Process process : children.values()) {
            if (process == null) continue;
            if (process.isAlive()) process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        children.clear();
    }

    private static String key(String snapshotId) {
        return snapshotId == null || snapshotId.isBlank() ? "live" : snapshotId;
    }

    private static void closeQuietly(WarmClassLoader loader) {
        try {
            loader.close();
        } catch (IOException ignored) {
            // close() already marks the loader closed before the jar release.
        }
    }
}
