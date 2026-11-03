package tools.dscode.control.protocol.speed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Handle for one long-lived worker JVM. A command reuses it when the pid is
 * alive and the watch hash still matches. Any mismatch deletes the handle.
 */
public final class WarmProcess {
    public static final String FILE_NAME = "warm-jvm.v1";
    /** Pid of a long-lived owner (Workbench, MCP host, or session). A one-shot CLI does not write this. */
    public static final String OWNER_FILE_NAME = "warm-owner.v1";
    /** Idle before a warm JVM exits. Blank, non-numeric, or non-positive config stays here. */
    public static final long DEFAULT_IDLE_SECONDS = 900;

    private WarmProcess() {
    }

    public record Handle(long pid, int port, String watchHash) {
    }

    public static Path file(Path stateRoot) {
        return stateRoot.resolve(FILE_NAME);
    }

    public static void write(Path stateRoot, Handle handle) throws IOException {
        if (handle == null || handle.pid() <= 0 || handle.port() <= 0) {
            throw new IllegalArgumentException("Warm JVM handle needs a pid and a port.");
        }
        Files.createDirectories(stateRoot);
        String body = "v1\n"
                + handle.pid() + "\n"
                + handle.port() + "\n"
                + (handle.watchHash() == null ? "" : handle.watchHash()) + "\n";
        Files.writeString(file(stateRoot), body, StandardCharsets.UTF_8);
    }

    public static Optional<Handle> read(Path stateRoot) {
        if (stateRoot == null) return Optional.empty();
        Path path = file(stateRoot);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.size() < 4 || !"v1".equals(lines.get(0).trim())) return Optional.empty();
            long pid = Long.parseLong(lines.get(1).trim());
            int port = Integer.parseInt(lines.get(2).trim());
            return Optional.of(new Handle(pid, port, lines.get(3).trim()));
        } catch (IOException | NumberFormatException failure) {
            return Optional.empty();
        }
    }

    public static boolean alive(long pid) {
        if (pid <= 0) return false;
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Alive and the same sources. A mismatch is not reusable. */
    public static Optional<Handle> reusable(Path stateRoot, String watchHash) {
        Optional<Handle> handle = read(stateRoot);
        if (handle.isEmpty()) return Optional.empty();
        Handle found = handle.get();
        if (!alive(found.pid())) {
            delete(stateRoot);
            return Optional.empty();
        }
        if (watchHash == null || !watchHash.equals(found.watchHash())) {
            return Optional.empty();
        }
        return handle;
    }

    /**
     * Deletes the handle and the owner claim, then kills that pid and its descendants.
     * Delete first so a replacement JVM can write a new handle while the old tree dies.
     */
    public static void release(Path stateRoot) {
        Optional<Handle> handle = read(stateRoot);
        delete(stateRoot);
        deleteOwner(stateRoot);
        handle.ifPresent(found -> ProcessTree.kill(found.pid()));
    }

    /**
     * Records a long-lived owner. The warm JVM adopts this pid and exits when it dies.
     * A short-lived CLI must not call this. No warm JVM yet is fine; the next start reads the file.
     */
    public static void claim(Path stateRoot, long pid) {
        if (stateRoot == null || pid <= 0) return;
        try {
            Files.createDirectories(stateRoot);
            Files.writeString(ownerFile(stateRoot), Long.toString(pid) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The next command starts with no owner rather than a partial claim.
        }
    }

    /** Alive owner pid, or zero. A dead claim is removed so the next warm JVM is not tied to it. */
    public static long liveOwner(Path stateRoot) {
        long pid = readOwner(stateRoot);
        if (pid <= 0) return 0L;
        if (alive(pid)) return pid;
        deleteOwner(stateRoot);
        return 0L;
    }

    /** Owner file contents, including a pid that has already exited. Zero when the file is absent. */
    public static long readOwner(Path stateRoot) {
        if (stateRoot == null) return 0L;
        Path path = ownerFile(stateRoot);
        if (!Files.isRegularFile(path)) return 0L;
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) return 0L;
            long pid = Long.parseLong(raw);
            return pid > 0 ? pid : 0L;
        } catch (IOException | NumberFormatException failure) {
            return 0L;
        }
    }

    public static Path ownerFile(Path stateRoot) {
        return stateRoot.resolve(OWNER_FILE_NAME);
    }

    public static void deleteOwner(Path stateRoot) {
        if (stateRoot == null) return;
        try {
            Files.deleteIfExists(ownerFile(stateRoot));
        } catch (IOException ignored) {
            // A leftover dead pid is ignored by liveOwner.
        }
    }

    public static void delete(Path stateRoot) {
        if (stateRoot == null) return;
        try {
            Files.deleteIfExists(file(stateRoot));
        } catch (IOException ignored) {
            // The next command treats a leftover file as stale.
        }
    }
}
