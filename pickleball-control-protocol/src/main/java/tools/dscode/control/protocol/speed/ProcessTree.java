package tools.dscode.control.protocol.speed;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        if (windows()) {
            killWindows(handle);
            return;
        }
        List<ProcessHandle> descendants = snapshot(handle);
        for (int index = descendants.size() - 1; index >= 0; index--) {
            destroy(descendants.get(index));
        }
        destroy(handle);
        awaitHandles(handle, descendants, System.nanoTime() + KILL_WAIT.toNanos());
    }

    /**
     * One {@code taskkill /F /T} of the root. A process-list walk is not used:
     * under load that snapshot blocks, and {@code /T} already covers the tree.
     * The {@code taskkill} process is left running if the wait expires. Destroying
     * it aborts the kill, and {@code destroyForcibly} does not end a Windows tree.
     */
    private static void killWindows(ProcessHandle handle) {
        long deadline = System.nanoTime() + KILL_WAIT.toNanos();
        taskkillUntilDead(handle.pid(), deadline);
        awaitHandle(handle, deadline);
        if (!alive(handle)) return;
        long retry = System.nanoTime() + KILL_WAIT.toNanos();
        taskkillUntilDead(handle.pid(), retry);
        awaitHandle(handle, retry);
    }

    /** Children only. The caller exits itself after this. */
    public static void killDescendants(ProcessHandle handle) {
        if (handle == null) return;
        long deadline = System.nanoTime() + KILL_WAIT.toNanos();
        if (windows()) {
            for (ProcessHandle child : childrenOf(handle)) {
                if (System.nanoTime() >= deadline) return;
                taskkillUntilDead(child.pid(), deadline);
                awaitHandle(child, deadline);
            }
            return;
        }
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

    private static List<ProcessHandle> childrenOf(ProcessHandle handle) {
        try {
            return handle.children().toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /** POSIX only. Windows uses {@link #taskkillUntilDead}. */
    private static void destroy(ProcessHandle handle) {
        if (handle == null) return;
        try {
            handle.destroyForcibly();
        } catch (RuntimeException ignored) {
            // The process can exit between the snapshot and the kill.
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Waits for {@code taskkill /F /T} on the process handle. Does not destroy
     * that {@code taskkill} when the deadline arrives.
     */
    private static void taskkillUntilDead(long pid, long deadlineNanos) {
        if (pid <= 0 || pid == ProcessHandle.current().pid()) return;
        if (!alivePid(pid)) return;
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMs <= 0) return;
        try {
            Process process = new ProcessBuilder(taskkillExecutable(), "/F", "/T", "/PID", Long.toString(pid))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            Optional<ProcessHandle> target = ProcessHandle.of(pid);
            if (target.isEmpty()) return;
            CompletableFuture.anyOf(target.get().onExit(), process.toHandle().onExit())
                    .get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
            // Leave taskkill running. Destroying it aborts the tree kill.
        } catch (ExecutionException ignored) {
            // The target handle completed.
        } catch (IOException failure) {
            try {
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            } catch (RuntimeException ignored) {
                // No taskkill and no handle left to signal.
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String taskkillExecutable() {
        String root = System.getenv("SystemRoot");
        if (root != null && !root.isBlank()) {
            return Path.of(root, "System32", "taskkill.exe").toString();
        }
        return "taskkill";
    }

    private static void awaitHandles(ProcessHandle handle, List<ProcessHandle> descendants, long deadlineNanos) {
        awaitHandle(handle, deadlineNanos);
        if (descendants == null) return;
        for (ProcessHandle descendant : descendants) {
            if (System.nanoTime() >= deadlineNanos) return;
            awaitHandle(descendant, deadlineNanos);
        }
    }

    private static void awaitHandle(ProcessHandle handle, long deadlineNanos) {
        if (handle == null || !alive(handle)) return;
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMs <= 0) return;
        try {
            handle.onExit().get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
            // The caller reports a pid that is still alive.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
            // The handle completed with the process already gone.
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
