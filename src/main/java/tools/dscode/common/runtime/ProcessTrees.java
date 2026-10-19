package tools.dscode.common.runtime;

import java.time.Duration;
import java.util.List;

/**
 * Kills a process and every descendant. Workbench uses this when a run watchdog
 * interrupts a wrapper, and when {@code stop --force} runs. A normal test run
 * does not call it.
 */
public final class ProcessTrees {
    public static final Duration KILL_WAIT = Duration.ofSeconds(10);

    private ProcessTrees() {
    }

    public static void killTree(ProcessHandle handle) {
        if (handle == null) return;
        List<ProcessHandle> descendants;
        try {
            descendants = handle.descendants().toList();
        } catch (RuntimeException ignored) {
            descendants = List.of();
        }
        for (int index = descendants.size() - 1; index >= 0; index--) {
            destroy(descendants.get(index));
        }
        destroy(handle);
        long deadline = System.nanoTime() + KILL_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!alive(handle) && descendants.stream().noneMatch(ProcessTrees::alive)) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
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
