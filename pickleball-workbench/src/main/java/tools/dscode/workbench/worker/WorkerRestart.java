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
}
