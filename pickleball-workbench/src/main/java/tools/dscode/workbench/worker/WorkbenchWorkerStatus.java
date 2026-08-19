package tools.dscode.workbench.worker;

/** Controller-side status for the one active interactive worker. */
public record WorkbenchWorkerStatus(
        boolean running,
        String sessionId,
        Long pid,
        String runtimeId,
        String scenarioId,
        boolean paused,
        Integer exitCode
) { }
