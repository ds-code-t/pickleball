package tools.dscode.workbench.worker;

import java.util.function.Supplier;

/**
 * A killed worker (non-zero exit) is still dead. Restart starts the next JVM.
 * The old gate treated any non-zero exit as a reason to stop and not start.
 */
final class WorkerRestart {
    private WorkerRestart() {
    }

    static boolean canStart(WorkbenchWorkerStatus stopped) {
        return stopped == null || !stopped.running();
    }

    static WorkbenchWorkerStatus afterUnhealthy(
            boolean unhealthy,
            WorkbenchWorkerStatus stopped,
            Supplier<WorkbenchWorkerStatus> start
    ) {
        if (!unhealthy) return stopped;
        if (!canStart(stopped)) {
            throw new IllegalStateException("Previous Workbench worker is still running.");
        }
        return start.get();
    }

    static final int FAILURES_BEFORE_RESTART = 2;

    /**
     * A dead worker process is a failed health check, the same as a live worker
     * whose health read is not ok. Two failures in a row ask for the
     * {@code worker-restart} path. A healthy live worker clears the count.
     */
    static HealthDecision note(int failures, boolean processAlive, boolean healthOk) {
        if (processAlive && healthOk) return new HealthDecision(0, false);
        int next = Math.max(0, failures) + 1;
        boolean restart = next >= FAILURES_BEFORE_RESTART;
        return new HealthDecision(restart ? 0 : next, restart);
    }

    record HealthDecision(int failures, boolean restart) {
    }
}
