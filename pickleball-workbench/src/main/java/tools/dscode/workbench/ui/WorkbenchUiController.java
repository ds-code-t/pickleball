package tools.dscode.workbench.ui;

import tools.dscode.workbench.WorkbenchServices;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.worker.WorkbenchWorkerStatus;

import java.nio.file.Path;

/** Thin presentation adapter over the shared Workbench service surface. */
final class WorkbenchUiController implements AutoCloseable {
    private final Path projectRoot;
    private final WorkbenchServices services;
    private WorkbenchManifest manifest;
    private String synchronizationError;
    private WorkbenchWorkerStatus workerStatus;

    WorkbenchUiController(Path projectRoot, WorkbenchServices services) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.services = services;
    }

    State refresh() {
        try {
            manifest = services.synchronizationStatus();
            synchronizationError = null;
        } catch (RuntimeException failure) {
            manifest = null;
            synchronizationError = failure.getMessage();
        }
        workerStatus = services.workerStatus();
        return state();
    }

    State synchronize() {
        manifest = services.synchronize();
        synchronizationError = null;
        workerStatus = services.workerStatus();
        return state();
    }

    State startWorker() {
        workerStatus = services.startWorker();
        return state();
    }

    State restartWorker() {
        workerStatus = services.restartWorker();
        return state();
    }

    State stopWorker() {
        workerStatus = services.stopWorker();
        return state();
    }

    @Override
    public void close() {
        services.close();
    }

    private State state() {
        return new State(projectRoot, manifest, synchronizationError, workerStatus);
    }

    record State(
            Path projectRoot,
            WorkbenchManifest manifest,
            String synchronizationError,
            WorkbenchWorkerStatus workerStatus
    ) {
        boolean synchronizedProject() {
            return manifest != null;
        }

        boolean workerRunning() {
            return workerStatus != null && workerStatus.running();
        }

        String render() {
            StringBuilder text = new StringBuilder("Project: ").append(projectRoot).append('\n');
            text.append("\nSynchronization\n");
            if (manifest != null) {
                text.append("  Type: ").append(manifest.projectType()).append('\n');
                text.append("  Synchronized: ").append(manifest.synchronizedAt()).append('\n');
                text.append("  Fingerprint: ").append(manifest.fingerprint()).append('\n');
                text.append("  Live output: ").append(manifest.liveOutput()).append('\n');
            } else {
                text.append("  Unavailable");
                if (synchronizationError != null && !synchronizationError.isBlank()) {
                    text.append(": ").append(synchronizationError);
                }
                text.append('\n');
            }

            text.append("\nWorker\n");
            if (workerStatus == null || !workerStatus.running()) {
                text.append("  Not running");
                if (workerStatus != null && workerStatus.exitCode() != null) {
                    text.append(" (exit=").append(workerStatus.exitCode()).append(')');
                }
                text.append('\n');
            } else {
                text.append("  PID: ").append(workerStatus.pid()).append('\n');
                text.append("  Paused: ").append(workerStatus.paused()).append('\n');
                text.append("  Runtime: ").append(workerStatus.runtimeId()).append('\n');
                text.append("  Scenario: ").append(workerStatus.scenarioId()).append('\n');
            }
            return text.toString();
        }
    }
}
