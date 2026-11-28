package tools.dscode.testsupport;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exclusive lock shared by test workers. The in-JVM gate waits inside one process.
 * {@link FileChannel#lock()} waits across processes. {@link #tryAcquire} returns null
 * when either gate is already held.
 */
public final class SerialFileLock implements AutoCloseable {
    private static final ConcurrentHashMap<String, ReentrantLock> GATES = new ConcurrentHashMap<>();

    private final FileChannel channel;
    private final FileLock lock;
    private final ReentrantLock gate;
    private boolean open = true;

    private SerialFileLock(FileChannel channel, FileLock lock, ReentrantLock gate) {
        this.channel = channel;
        this.lock = lock;
        this.gate = gate;
    }

    public static SerialFileLock acquire(Path directory, String name) throws IOException {
        ReentrantLock gate = gate(directory, name);
        gate.lock();
        FileChannel channel = null;
        try {
            channel = open(directory, name);
            FileLock lock = channel.lock();
            return new SerialFileLock(channel, lock, gate);
        } catch (IOException | RuntimeException failure) {
            if (channel != null) channel.close();
            gate.unlock();
            throw failure;
        }
    }

    /** @return null when this JVM or another process already holds {@code name} */
    public static SerialFileLock tryAcquire(Path directory, String name) throws IOException {
        ReentrantLock gate = gate(directory, name);
        if (!gate.tryLock()) return null;
        FileChannel channel = null;
        try {
            channel = open(directory, name);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException held) {
                channel.close();
                gate.unlock();
                return null;
            }
            if (lock == null) {
                channel.close();
                gate.unlock();
                return null;
            }
            return new SerialFileLock(channel, lock, gate);
        } catch (IOException | RuntimeException failure) {
            if (channel != null) channel.close();
            gate.unlock();
            throw failure;
        }
    }

    public static Path directory() {
        String configured = System.getProperty("pkb.test.lockDir");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        return Path.of("build", "pkb-test-locks");
    }

    private static ReentrantLock gate(Path directory, String name) {
        String key = directory.toAbsolutePath().normalize() + "|" + sanitize(name);
        return GATES.computeIfAbsent(key, ignored -> new ReentrantLock());
    }

    private static FileChannel open(Path directory, String name) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(sanitize(name) + ".lock");
        return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    private static String sanitize(String name) {
        String raw = name == null ? "" : name;
        String safe = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "resource" : safe;
    }

    @Override
    public void close() {
        if (!open) return;
        open = false;
        try {
            if (lock.isValid()) lock.release();
        } catch (IOException ignored) {
            // The channel close still drops the lock.
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Closing a released lock is enough.
        }
        gate.unlock();
    }
}
