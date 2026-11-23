package tools.dscode.control.protocol.speed;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Kills a process and every descendant. The launcher, the warm JVM, and
 * Workbench session teardown share this path. {@code System.exit} does not
 * kill children, so a self-exit calls {@link #killDescendants} first.
 */
public final class ProcessTree {
    public static final Duration KILL_WAIT = Duration.ofSeconds(10);

    private ProcessTree() {
    }

    public static void kill(long pid) {
        if (pid <= 0) return;
        ProcessHandle.of(pid).ifPresent(ProcessTree::kill);
    }

    /**
     * Kills {@code pid} only when its start instant still matches {@code startedEpochMilli}.
     * {@code startedEpochMilli <= 0} means the start was not recorded, so the pid is killed.
     * Returns false when the process is already gone or the start instant moved (pid reuse).
     */
    public static boolean killIfStarted(long pid, long startedEpochMilli) {
        if (pid <= 0) return false;
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) return false;
        if (!sameStart(handle.get(), startedEpochMilli)) return false;
        kill(handle.get());
        return true;
    }

    /** True when {@code startedEpochMilli} was not recorded or equals this process's start. */
    public static boolean sameStart(ProcessHandle handle, long startedEpochMilli) {
        if (handle == null) return false;
        if (startedEpochMilli <= 0) return true;
        Optional<java.time.Instant> actual = handle.info().startInstant();
        if (actual.isEmpty()) return true;
        return actual.get().toEpochMilli() == startedEpochMilli;
    }

    public static void kill(ProcessHandle handle) {
        if (handle == null) return;
        List<ProcessHandle> descendants = snapshot(handle);
        for (int index = descendants.size() - 1; index >= 0; index--) {
            destroy(descendants.get(index));
        }
        destroy(handle);
        long deadline = System.nanoTime() + KILL_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!alive(handle) && descendants.stream().noneMatch(ProcessTree::alive)) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Children only. The caller exits itself after this. */
    public static void killDescendants(ProcessHandle handle) {
        if (handle == null) return;
        List<ProcessHandle> descendants = snapshot(handle);
        for (int index = descendants.size() - 1; index >= 0; index--) {
            destroy(descendants.get(index));
        }
    }

    private static List<ProcessHandle> snapshot(ProcessHandle handle) {
        try {
            return handle.descendants().toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /**
     * Windows {@code ProcessHandle.destroyForcibly} leaves the process and its
     * children alive. {@code taskkill /F /T} ends that tree. POSIX keeps
     * {@code destroyForcibly}.
     */
    private static void destroy(ProcessHandle handle) {
        if (handle == null) return;
        if (windows() && taskkillTree(handle.pid())) return;
        try {
            handle.destroyForcibly();
        } catch (RuntimeException ignored) {
            // The process can exit between the snapshot and the kill.
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean taskkillTree(long pid) {
        if (pid <= 0 || pid == ProcessHandle.current().pid()) return false;
        String executable = "taskkill";
        String root = System.getenv("SystemRoot");
        if (root != null && !root.isBlank()) {
            executable = Path.of(root, "System32", "taskkill.exe").toString();
        }
        try {
            Process process = new ProcessBuilder(executable, "/F", "/T", "/PID", Long.toString(pid))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0 || !alivePid(pid);
        } catch (IOException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean alivePid(long pid) {
        return ProcessHandle.of(pid).map(ProcessTree::alive).orElse(false);
    }

    private static boolean alive(ProcessHandle handle) {
        try {
            return handle.isAlive();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
