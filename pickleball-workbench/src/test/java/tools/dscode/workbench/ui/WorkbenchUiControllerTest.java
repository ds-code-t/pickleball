package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;
import tools.dscode.control.protocol.ControlBridgeBreakpoint;
import tools.dscode.control.protocol.ControlBridgeBrowserPage;
import tools.dscode.control.protocol.ControlBridgeBrowserPageResult;
import tools.dscode.control.protocol.ControlBridgeBrowserScreenshot;
import tools.dscode.control.protocol.ControlBridgeBrowserScreenshotResult;
import tools.dscode.control.protocol.ControlBridgeCallResult;
import tools.dscode.control.protocol.ControlBridgeEvent;
import tools.dscode.control.protocol.ControlBridgeEventPage;
import tools.dscode.control.protocol.ControlBridgeServiceCallEvidence;
import tools.dscode.control.protocol.ControlBridgeServiceCallResult;
import tools.dscode.control.protocol.ControlBridgeStatus;
import tools.dscode.control.protocol.ControlBridgeStepOverride;
import tools.dscode.control.protocol.ControlBridgeStepOverrideResult;
import tools.dscode.control.protocol.ControlBridgeValue;
import tools.dscode.control.protocol.ControlBridgeValueResult;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.workbench.WorkbenchServices;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.worker.WorkbenchWorkerStatus;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        assertTrue(step.events().contains("#1 2026-08-19T00:00:00Z AFTER_STEP"));
        assertTrue(step.events().contains("step: CONTROL API TEST STEP"));
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
    void stepOverrideEvidenceAndBreakpointActionsDelegateToSharedWorkbenchServices() {
        RecordingServices recording = new RecordingServices();
        WorkbenchUiController controller = new WorkbenchUiController(
                Path.of("consumer"),
                recording.services()
        );

        assertEquals("No Step Overrides.", controller.stepOverrides());
        WorkbenchUiController.ManagementResult compiled = controller.compileStepOverride(
                "workbench-ui-generated",
                "^WORKBENCH UI OVERRIDE ([A-Za-z]+)$",
                "public final class {{CLASS_NAME}} {}"
        );
        WorkbenchUiController.ManagementResult removedOverride = controller.removeStepOverride(
                "workbench-ui-generated"
        );
        controller.compileStepOverride(
                "workbench-ui-generated",
                "^WORKBENCH UI OVERRIDE ([A-Za-z]+)$",
                "public final class {{CLASS_NAME}} {}"
        );
        WorkbenchUiController.ManagementResult clearedOverrides = controller.clearStepOverrides();

        WorkbenchUiController.LiveActionResult page = controller.browserPage();
        WorkbenchUiController.ScreenshotResult screenshot = controller.browserScreenshot();
        WorkbenchUiController.LiveActionResult service = controller.serviceCall("%health-full-url");

        assertEquals("No breakpoints.", controller.breakpoints());
        WorkbenchUiController.ManagementResult addedBreakpoint = controller.addBreakpoint(
                "BEFORE_STEP",
                "",
                "CONTROL API TEST STEP",
                "",
                true,
                "120"
        );
        WorkbenchUiController.ManagementResult removedBreakpoint = controller.removeBreakpoint("bp-1");
        controller.addBreakpoint("BEFORE_STEP", "", "CONTROL API TEST STEP", "", true, "120");
        WorkbenchUiController.ManagementResult clearedBreakpoints = controller.clearBreakpoints();

        assertTrue(compiled.output().contains("Status: SUCCESS"));
        assertTrue(compiled.listing().contains("workbench-ui-generated | REGEX"));
        assertTrue(removedOverride.output().contains("Removed: true"));
        assertTrue(clearedOverrides.output().contains("Removed: 1"));
        assertTrue(clearedOverrides.listing().contains("No Step Overrides."));

        assertTrue(page.output().contains("URL: http://127.0.0.1:8080/"));
        assertTrue(page.output().contains("Title: Pickleball Test Lab"));
        assertTrue(screenshot.output().contains("Screenshot: image/png | 4 bytes"));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, screenshot.png());
        assertTrue(service.output().contains("HTTP status: 200"));
        assertTrue(service.output().contains("Selector: %health-full-url"));

        assertTrue(addedBreakpoint.output().contains("Added: bp-1"));
        assertTrue(addedBreakpoint.listing().contains("step~CONTROL API TEST STEP"));
        assertTrue(removedBreakpoint.output().contains("Removed: true"));
        assertTrue(clearedBreakpoints.output().contains("Removed: 1"));
        assertTrue(clearedBreakpoints.listing().contains("No breakpoints."));

        assertTrue(recording.calls.contains("stepOverrides"));
        assertTrue(recording.calls.contains("compileStepOverride:workbench-ui-generated"));
        assertTrue(recording.calls.contains("removeStepOverride:workbench-ui-generated"));
        assertTrue(recording.calls.contains("clearStepOverrides"));
        assertTrue(recording.calls.contains("browserPage"));
        assertTrue(recording.calls.contains("browserScreenshot"));
        assertTrue(recording.calls.contains("serviceCall:%health-full-url"));
        assertTrue(recording.calls.contains("breakpoints"));
        assertTrue(recording.calls.contains("addBreakpoint:BEFORE_STEP:CONTROL API TEST STEP:true:120"));
        assertTrue(recording.calls.contains("removeBreakpoint:bp-1"));
        assertTrue(recording.calls.contains("clearBreakpoints"));
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
                ControlProtocol.CURRENT_VERSION,
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
        private final List<ControlBridgeStepOverride> overrides = new ArrayList<>();
        private final List<ControlBridgeBreakpoint> breakpoints = new ArrayList<>();
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
                        case "stepOverrides" -> {
                            calls.add("stepOverrides");
                            yield List.copyOf(overrides);
                        }
                        case "compileStepOverride" -> {
                            calls.add("compileStepOverride:" + args[0]);
                            ControlBridgeStepOverride override = new ControlBridgeStepOverride(
                                    (String) args[0], "REGEX", (String) args[1], "GeneratedHandler"
                            );
                            overrides.removeIf(existing -> existing.id().equals(args[0]));
                            overrides.add(override);
                            yield new ControlBridgeStepOverrideResult(
                                    "SUCCESS", override, null, runtime()
                            );
                        }
                        case "removeStepOverride" -> {
                            calls.add("removeStepOverride:" + args[0]);
                            yield overrides.removeIf(existing -> existing.id().equals(args[0]));
                        }
                        case "clearStepOverrides" -> {
                            calls.add("clearStepOverrides");
                            int count = overrides.size();
                            overrides.clear();
                            yield count;
                        }
                        case "browserPage" -> {
                            calls.add("browserPage");
                            yield new ControlBridgeBrowserPageResult(
                                    "SUCCESS",
                                    new ControlBridgeBrowserPage(
                                            "http://127.0.0.1:8080/",
                                            "Pickleball Test Lab",
                                            "window-1",
                                            List.of("window-1"),
                                            1280,
                                            720,
                                            "<html>Pickleball Test Lab</html>",
                                            false
                                    ),
                                    null,
                                    runtime()
                            );
                        }
                        case "browserScreenshot" -> {
                            calls.add("browserScreenshot");
                            byte[] png = {1, 2, 3, 4};
                            yield new ControlBridgeBrowserScreenshotResult(
                                    "SUCCESS",
                                    new ControlBridgeBrowserScreenshot(
                                            "image/png",
                                            png.length,
                                            Base64.getEncoder().encodeToString(png)
                                    ),
                                    null,
                                    runtime()
                            );
                        }
                        case "serviceCall" -> {
                            calls.add("serviceCall:" + args[0]);
                            yield new ControlBridgeServiceCallResult(
                                    "SUCCESS",
                                    new ControlBridgeServiceCallEvidence(
                                            (String) args[0], null, null, null, 200
                                    ),
                                    null,
                                    runtime()
                            );
                        }
                        case "breakpoints" -> {
                            calls.add("breakpoints");
                            yield List.copyOf(breakpoints);
                        }
                        case "addBreakpoint" -> {
                            calls.add("addBreakpoint:" + args[0] + ":" + args[2] + ":" + args[4] + ":" + args[5]);
                            ControlBridgeBreakpoint breakpoint = new ControlBridgeBreakpoint(
                                    "bp-1",
                                    "scenario-101",
                                    (String) args[0],
                                    (String) args[1],
                                    (String) args[2],
                                    (String) args[3],
                                    (Boolean) args[4],
                                    args[5] == null ? 120 : (Integer) args[5],
                                    0,
                                    null,
                                    null
                            );
                            breakpoints.clear();
                            breakpoints.add(breakpoint);
                            yield breakpoint;
                        }
                        case "removeBreakpoint" -> {
                            calls.add("removeBreakpoint:" + args[0]);
                            yield breakpoints.removeIf(existing -> existing.breakpointId().equals(args[0]));
                        }
                        case "clearBreakpoints" -> {
                            calls.add("clearBreakpoints");
                            int count = breakpoints.size();
                            breakpoints.clear();
                            yield count;
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
