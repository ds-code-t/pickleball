package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import tools.dscode.workbench.WorkbenchServices;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.worker.WorkbenchWorkerStatus;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchUiControllerTest {

    @Test
    void workerLifecycleDelegatesToSharedWorkbenchServices() {
        RecordingServices recording = new RecordingServices();
        WorkbenchUiController controller = new WorkbenchUiController(
                Path.of("consumer"),
                recording.services()
        );

        WorkbenchUiController.State refreshed = controller.refresh();
        WorkbenchUiController.State synchronizedState = controller.synchronize();
        WorkbenchUiController.State started = controller.startWorker();
        WorkbenchUiController.State restarted = controller.restartWorker();
        WorkbenchUiController.State stopped = controller.stopWorker();
        controller.close();

        assertTrue(refreshed.synchronizedProject());
        assertTrue(synchronizedState.synchronizedProject());
        assertTrue(started.workerRunning());
        assertEquals(101L, started.workerStatus().pid());
        assertEquals(202L, restarted.workerStatus().pid());
        assertFalse(stopped.workerRunning());
        assertEquals(List.of(
                "synchronizationStatus",
                "workerStatus",
                "synchronize",
                "workerStatus",
                "startWorker",
                "restartWorker",
                "stopWorker",
                "close"
        ), recording.calls);
    }

    @Test
    void refreshKeepsSynchronizationFailureVisibleWithoutBlockingWorkerStatus() {
        RecordingServices recording = new RecordingServices();
        recording.synchronizationFailure = new IllegalStateException("Run sync first");
        WorkbenchUiController controller = new WorkbenchUiController(
                Path.of("consumer"),
                recording.services()
        );

        WorkbenchUiController.State state = controller.refresh();

        assertFalse(state.synchronizedProject());
        assertFalse(state.workerRunning());
        assertTrue(state.render().contains("Run sync first"));
        assertTrue(state.render().contains("Worker\n  Not running"));
    }

    private static WorkbenchManifest manifest() {
        return new WorkbenchManifest(
                WorkbenchManifest.CURRENT_SCHEMA,
                Path.of("consumer").toAbsolutePath().normalize().toString(),
                "MAVEN",
                "MAVEN",
                List.of(),
                List.of(),
                List.of(),
                Path.of("consumer", ".pickleball", "workbench", "live", "classes")
                        .toAbsolutePath().normalize().toString(),
                "2026-08-19T00:00:00Z",
                "fingerprint",
                List.of(),
                "2.1.8",
                "21",
                Path.of("java-home").toAbsolutePath().normalize().toString()
        );
    }

    private static WorkbenchWorkerStatus running(long pid) {
        return new WorkbenchWorkerStatus(
                true,
                "session-" + pid,
                pid,
                "runtime-" + pid,
                "scenario-" + pid,
                true,
                null
        );
    }

    private static final class RecordingServices {
        private final List<String> calls = new java.util.ArrayList<>();
        private RuntimeException synchronizationFailure;
        private WorkbenchWorkerStatus status = new WorkbenchWorkerStatus(
                false, null, null, null, null, false, null
        );
        private int starts;

        WorkbenchServices services() {
            return (WorkbenchServices) Proxy.newProxyInstance(
                    WorkbenchServices.class.getClassLoader(),
                    new Class<?>[]{WorkbenchServices.class},
                    (proxy, method, args) -> {
                        calls.add(method.getName());
                        return switch (method.getName()) {
                            case "synchronizationStatus" -> {
                                if (synchronizationFailure != null) throw synchronizationFailure;
                                yield manifest();
                            }
                            case "synchronize" -> manifest();
                            case "workerStatus" -> status;
                            case "startWorker" -> status = running(++starts == 1 ? 101L : 202L);
                            case "restartWorker" -> status = running(202L);
                            case "stopWorker" -> status = new WorkbenchWorkerStatus(
                                    false, null, null, null, null, false, 0
                            );
                            case "close" -> null;
                            case "toString" -> "RecordingWorkbenchServices";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new AssertionError("Unexpected service call: " + method.getName());
                        };
                    }
            );
        }
    }
}
