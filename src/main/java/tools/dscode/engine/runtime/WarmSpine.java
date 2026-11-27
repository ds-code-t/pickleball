package tools.dscode.engine.runtime;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * One {@link WarmClassLoader} per snapshot id. Replacing a loader closes the old one
 * and removes that map entry. The loader is not stored in a {@code ThreadLocal}.
 */
public final class WarmSpine {
    private static final WarmSpine SHARED = new WarmSpine();

    private final ConcurrentHashMap<String, WarmClassLoader> loaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> stamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Process> children = new ConcurrentHashMap<>();
    private volatile long lastTrackedPid;

    public WarmSpine() {
    }

    public static WarmSpine shared() {
        return SHARED;
    }

    public WarmClassLoader loader(String snapshotId, URL[] urls, Collection<String> packages) {
        String key = key(snapshotId);
        String stamp = classesStamp(urls);
        WarmClassLoader existing = loaders.get(key);
        if (existing != null && !existing.isClosed() && stamp.equals(stamps.get(key))) return existing;
        WarmClassLoader created = new WarmClassLoader(urls, WarmClassLoader.spineLoader(), packages);
        stamps.put(key, stamp);
        WarmClassLoader previous = loaders.put(key, created);
        if (previous != null && previous != created) closeQuietly(previous);
        return created;
    }

    /** Closes the previous loader for this snapshot and drops that map entry. */
    public WarmClassLoader replace(String snapshotId, WarmClassLoader next) {
        String key = key(snapshotId);
        if (next == null) throw new IllegalArgumentException("warm loader is required");
        stamps.put(key, classesStamp(next.getURLs()));
        WarmClassLoader previous = loaders.put(key, next);
        if (previous != null && previous != next) closeQuietly(previous);
        return next;
    }

    /** Drops the entry when it is still {@code loader}. Does not close it. */
    public void detach(String snapshotId, WarmClassLoader loader) {
        if (loader == null) return;
        String key = key(snapshotId);
        if (loaders.remove(key, loader)) stamps.remove(key);
    }

    public WarmClassLoader get(String snapshotId) {
        return loaders.get(key(snapshotId));
    }

    public void reset() {
        stopChildren();
        for (WarmClassLoader loader : loaders.values()) closeQuietly(loader);
        loaders.clear();
        stamps.clear();
    }

    /** Live subprocess JVMs. A process that has already exited is dropped. */
    public int liveChildren() {
        children.entrySet().removeIf(entry -> entry.getValue() == null || !entry.getValue().isAlive());
        return children.size();
    }

    public Process track(Process process) {
        if (process == null) throw new IllegalArgumentException("subprocess is required");
        lastTrackedPid = process.pid();
        children.put(process.pid(), process);
        return process;
    }

    /** Pid of the child most recently admitted. Stays after that process exits. */
    public long lastTrackedPid() {
        return lastTrackedPid;
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

    /**
     * Identity of the class files behind {@code urls}. A later context replaces its loader
     * when this value changes.
     */
    private static String classesStamp(URL[] urls) {
        if (urls == null || urls.length == 0) return "empty";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (URL url : urls) {
                if (url == null) continue;
                digest.update(url.toExternalForm().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                Path path = urlPath(url);
                if (path == null || !Files.isDirectory(path)) continue;
                try (var walk = Files.walk(path)) {
                    List<Path> files = walk.filter(Files::isRegularFile)
                            .filter(file -> file.getFileName().toString().endsWith(".class"))
                            .sorted()
                            .toList();
                    for (Path file : files) {
                        String relative = path.relativize(file).toString().replace('\\', '/');
                        digest.update(relative.getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        digest.update(Long.toString(Files.getLastModifiedTime(file).toMillis()).getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            return "unstamped-" + System.nanoTime();
        }
    }

    private static Path urlPath(URL url) {
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException | IllegalArgumentException failure) {
            return null;
        }
    }

    private static void closeQuietly(WarmClassLoader loader) {
        try {
            loader.close();
        } catch (IOException ignored) {
            // close() already marks the loader closed before the jar release.
        }
    }
}
