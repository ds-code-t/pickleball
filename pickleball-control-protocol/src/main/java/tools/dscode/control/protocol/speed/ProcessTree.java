package tools.dscode.control.protocol.speed;

import java.time.Duration;
import java.util.List;

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

    private static void destroy(ProcessHandle handle) {
        try {
            handle.destroyForcibly();
        } catch (RuntimeException ignored) {
            // The process can exit between the snapshot and the kill.
        }
    }

    private static boolean alive(ProcessHandle handle) {
        try {
            return handle.isAlive();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
