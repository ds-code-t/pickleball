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

    public static void delete(Path stateRoot) {
        if (stateRoot == null) return;
        try {
            Files.deleteIfExists(file(stateRoot));
        } catch (IOException ignored) {
            // The next command treats a leftover file as stale.
        }
    }
}
