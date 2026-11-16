package tools.dscode.store.sql;

import tools.dscode.store.StorePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows can keep a closed SQLite file mapped for a moment. Callers that
 * delete the project wait, bounded, and probe by renaming each file aside
 * and back. A file still held at the deadline marks the store directory
 * {@code pending-delete}.
 */
public final class StoreFiles {
    /** Upper bound for a release wait. */
    public static final long RELEASE_MS = 15_000L;

    @FunctionalInterface
    public interface Probe {
        void check(Path file) throws IOException;
    }

    private StoreFiles() {
    }

    public static Path pendingDelete(Path project) {
        return StorePaths.root(project).resolve("pending-delete");
    }

    /**
     * True when {@code file} is absent or the probe passes before the deadline.
     * False when the file is still held. Never throws for a held file.
     */
    public static boolean awaitRelease(Path file, long timeoutMs, Probe probe) {
        if (file == null || !Files.isRegularFile(file)) return true;
        long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
        while (true) {
            try {
                probe.check(file);
                return true;
            } catch (IOException busy) {
                if (!Files.isRegularFile(file)) return true;
            }
            if (System.nanoTime() >= deadline) return false;
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Renames the file aside and back. Windows refuses the rename while another process holds it. */
    public static void renameProbe(Path file) throws IOException {
        Path aside = file.resolveSibling(file.getFileName().toString() + ".release-check");
        Files.move(file, aside);
        Files.move(aside, file);
    }

    /**
     * Waits for the project database files, their WAL and shared-memory sidecars,
     * and each sandbox {@code source.db}. Writes {@code pending-delete} when one
     * is still held and removes that marker when every file is free.
     */
    public static boolean releaseProject(Path project, long timeoutMs) throws IOException {
        List<Path> files = new ArrayList<>();
        files.add(StorePaths.mainDatabase(project));
        files.add(StorePaths.runState(project));
        Path sandboxes = StorePaths.root(project).resolve("sandboxes");
        if (Files.isDirectory(sandboxes)) {
            try (var stream = Files.list(sandboxes)) {
                for (Path dir : stream.toList()) files.add(dir.resolve("source.db"));
            }
        }
        List<String> held = new ArrayList<>();
        for (Path file : files) {
            if (!freeFamily(file, timeoutMs)) held.add(file.toAbsolutePath().normalize().toString());
        }
        return writeMarker(pendingDelete(project), held);
    }

    /** Test seam: the probe stands in for a file another process still holds. */
    public static boolean releaseFiles(List<Path> files, Path marker, long timeoutMs, Probe probe) throws IOException {
        List<String> held = new ArrayList<>();
        for (Path file : files) {
            if (!awaitRelease(file, timeoutMs, probe)) {
                held.add(file.toAbsolutePath().normalize().toString());
            }
        }
        return writeMarker(marker, held);
    }

    private static boolean writeMarker(Path marker, List<String> held) throws IOException {
        if (held.isEmpty()) {
            Files.deleteIfExists(marker);
            return true;
        }
        if (marker.getParent() != null) Files.createDirectories(marker.getParent());
        Files.writeString(marker, String.join("\n", held) + "\n", StandardCharsets.UTF_8);
        return false;
    }

    private static boolean freeFamily(Path file, long timeoutMs) {
        return awaitRelease(file, timeoutMs, StoreFiles::renameProbe)
                && awaitRelease(Path.of(file.toString() + "-wal"), timeoutMs, StoreFiles::renameProbe)
                && awaitRelease(Path.of(file.toString() + "-shm"), timeoutMs, StoreFiles::renameProbe);
    }
}
