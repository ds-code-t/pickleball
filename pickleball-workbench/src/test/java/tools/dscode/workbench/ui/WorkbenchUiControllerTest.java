package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import tools.dscode.control.bridge.ControlBridgeCallResult;
import tools.dscode.control.bridge.ControlBridgeEvent;
import tools.dscode.control.bridge.ControlBridgeEventPage;
import tools.dscode.control.bridge.ControlBridgeStatus;
import tools.dscode.control.bridge.ControlBridgeValue;
import tools.dscode.control.bridge.ControlBridgeValueResult;
import tools.dscode.workbench.WorkbenchServices;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.worker.WorkbenchWorkerStatus;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
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
        assertTrue(started.liveReady());
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
    void liveGherkinAndMappingDelegateToSharedWorkbenchServicesAndRefreshEvents() {
        RecordingServices recording = new RecordingServices();
        WorkbenchUiController controller = new WorkbenchUiController(
                Path.of("consumer"),
                recording.services()
        );

        WorkbenchUiController.LiveActionResult step = controller.executeStep(
                "CONTROL API TEST STEP",
                ""
        );
        WorkbenchUiController.LiveActionResult put = controller.mappingPut(
                "OVERRIDE",
                "workbenchLiveValue",
                "first"
        );
        WorkbenchUiController.LiveActionResult get = controller.mappingGet(
                "OVERRIDE",
                "workbenchLiveValue"
        );
        WorkbenchUiController.LiveActionResult resolve = controller.mappingResolve(
                "<workbenchLiveValue>"
        );

        assertTrue(step.output().contains("Status: SUCCESS"));
        assertTrue(step.events().contains("#1 AFTER_STEP | CONTROL API TEST STEP"));
        assertTrue(put.output().contains("Value (STRING): first"));
        assertTrue(get.output().contains("Value (STRING): first"));
        assertTrue(resolve.output().contains("Value (STRING): first"));
        assertEquals(List.of(
                "executeStep:CONTROL API TEST STEP:null",
                "events:0",
                "mappingPut:OVERRIDE:workbenchLiveValue:first",
                "events:1",
                "mappingGet:OVERRIDE:workbenchLiveValue",
                "events:2",
                "mappingResolve:<workbenchLiveValue>",
                "events:3"
        ), recording.calls);
    }

    @Test
    void restartResetsSemanticEventCursorForFreshWorker() {
        RecordingServices recording = new RecordingServices();
        WorkbenchUiController controller = new WorkbenchUiController(
                Path.of("consumer"),
                recording.services()
        );

        controller.refreshEvents();
        controller.restartWorker();
        controller.refreshEvents();

        assertEquals(List.of("events:0", "restartWorker", "events:0"), recording.calls);
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

    private static ControlBridgeStatus runtime() {
        return new ControlBridgeStatus(
                1,
                "runtime-101",
                101L,
                1,
                1L,
                "scenario-101",
                "Workbench",
                "CONTROL API TEST STEP",
                null,
                "AFTER_STEP",
                "step-signature",
                true,
                false,
                List.of("events")
        );
    }

    private static final class RecordingServices {
        private final List<String> calls = new ArrayList<>();
        private RuntimeException synchronizationFailure;
        private WorkbenchWorkerStatus status = new WorkbenchWorkerStatus(
                false, null, null, null, null, false, null
        );
        private int starts;
        private long eventSequence;

        WorkbenchServices services() {
            return (WorkbenchServices) Proxy.newProxyInstance(
                    WorkbenchServices.class.getClassLoader(),
                    new Class<?>[]{WorkbenchServices.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "synchronizationStatus" -> {
                            calls.add("synchronizationStatus");
                            if (synchronizationFailure != null) throw synchronizationFailure;
                            yield manifest();
                        }
                        case "synchronize" -> {
                            calls.add("synchronize");
                            yield manifest();
                        }
                        case "workerStatus" -> {
                            calls.add("workerStatus");
                            yield status;
                        }
                        case "startWorker" -> {
                            calls.add("startWorker");
                            eventSequence = 0;
                            yield status = running(++starts == 1 ? 101L : 202L);
                        }
                        case "restartWorker" -> {
                            calls.add("restartWorker");
                            eventSequence = 0;
                            yield status = running(202L);
                        }
                        case "stopWorker" -> {
                            calls.add("stopWorker");
                            eventSequence = 0;
                            yield status = new WorkbenchWorkerStatus(
                                    false, null, null, null, null, false, 0
                            );
                        }
                        case "executeStep" -> {
                            calls.add("executeStep:" + args[0] + ":" + args[1]);
                            yield new ControlBridgeCallResult(
                                    "SUCCESS", "STRING", "executed", null, runtime()
                            );
                        }
                        case "mappingPut" -> {
                            calls.add("mappingPut:" + args[0] + ":" + args[1] + ":" + args[2]);
                            yield valueResult("first");
                        }
                        case "mappingGet" -> {
                            calls.add("mappingGet:" + args[0] + ":" + args[1]);
                            yield valueResult("first");
                        }
                        case "mappingResolve" -> {
                            calls.add("mappingResolve:" + args[0]);
                            yield valueResult("first");
                        }
                        case "events" -> {
                            long after = (Long) args[0];
                            calls.add("events:" + after);
                            long sequence = ++eventSequence;
                            yield new ControlBridgeEventPage(
                                    List.of(new ControlBridgeEvent(
                                            sequence,
                                            "2026-08-19T00:00:00Z",
                                            1L,
                                            "scenario-101",
                                            "Workbench",
                                            "AFTER_STEP",
                                            "step-signature",
                                            "CONTROL API TEST STEP",
                                            null
                                    )),
                                    sequence,
                                    1L,
                                    sequence,
                                    false,
                                    false
                            );
                        }
                        case "close" -> {
                            calls.add("close");
                            yield null;
                        }
                        case "toString" -> "RecordingWorkbenchServices";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new AssertionError("Unexpected service call: " + method.getName());
                    }
            );
        }

        private static ControlBridgeValueResult valueResult(String value) {
            return new ControlBridgeValueResult(
                    "SUCCESS",
                    new ControlBridgeValue("STRING", true, value, value),
                    null,
                    runtime()
            );
        }
    }
}
