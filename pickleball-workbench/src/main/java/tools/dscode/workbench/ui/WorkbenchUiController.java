package tools.dscode.workbench.ui;

import tools.dscode.control.bridge.ControlBridgeCallResult;
import tools.dscode.control.bridge.ControlBridgeError;
import tools.dscode.control.bridge.ControlBridgeEvent;
import tools.dscode.control.bridge.ControlBridgeEventPage;
import tools.dscode.control.bridge.ControlBridgeStatus;
import tools.dscode.control.bridge.ControlBridgeValue;
import tools.dscode.control.bridge.ControlBridgeValueResult;
import tools.dscode.workbench.WorkbenchServices;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.worker.WorkbenchWorkerStatus;

import java.nio.file.Path;

/** Thin presentation adapter over the shared Workbench service surface. */
final class WorkbenchUiController implements AutoCloseable {
    private static final int EVENT_PAGE_SIZE = 100;

    private final Path projectRoot;
    private final WorkbenchServices services;
    private WorkbenchManifest manifest;
    private String synchronizationError;
    private WorkbenchWorkerStatus workerStatus;
    private long eventSequence;

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
        eventSequence = 0;
        workerStatus = services.startWorker();
        return state();
    }

    State restartWorker() {
        eventSequence = 0;
        workerStatus = services.restartWorker();
        return state();
    }

    State stopWorker() {
        eventSequence = 0;
        workerStatus = services.stopWorker();
        return state();
    }

    LiveActionResult executeStep(String text, String argument) {
        ControlBridgeCallResult result = services.executeStep(required(text, "Gherkin step"), blankToNull(argument));
        return new LiveActionResult(renderCallResult(result), refreshEvents());
    }

    LiveActionResult mappingGet(String mapReference, String key) {
        ControlBridgeValueResult result = services.mappingGet(
                required(mapReference, "Mapping reference"),
                required(key, "Mapping key")
        );
        return new LiveActionResult(renderValueResult(result), refreshEvents());
    }

    LiveActionResult mappingPut(String mapReference, String key, String value) {
        ControlBridgeValueResult result = services.mappingPut(
                required(mapReference, "Mapping reference"),
                required(key, "Mapping key"),
                value == null ? "" : value
        );
        return new LiveActionResult(renderValueResult(result), refreshEvents());
    }

    LiveActionResult mappingResolve(String input) {
        ControlBridgeValueResult result = services.mappingResolve(required(input, "Mapping input"));
        return new LiveActionResult(renderValueResult(result), refreshEvents());
    }

    String refreshEvents() {
        ControlBridgeEventPage page = services.events(eventSequence, EVENT_PAGE_SIZE);
        eventSequence = page.nextSequence();
        return renderEvents(page);
    }

    @Override
    public void close() {
        services.close();
    }

    private State state() {
        return new State(projectRoot, manifest, synchronizationError, workerStatus);
    }

    private static String renderCallResult(ControlBridgeCallResult result) {
        StringBuilder text = new StringBuilder("Status: ").append(result.status());
        if (result.valueText() != null) {
            text.append("\nValue");
            if (result.valueType() != null) text.append(" (").append(result.valueType()).append(')');
            text.append(": ").append(result.valueText());
        }
        appendError(text, result.error());
        appendRuntime(text, result.runtime());
        return text.toString();
    }

    private static String renderValueResult(ControlBridgeValueResult result) {
        StringBuilder text = new StringBuilder("Status: ").append(result.status());
        ControlBridgeValue value = result.value();
        if (value != null) {
            text.append("\nValue");
            if (value.type() != null) text.append(" (").append(value.type()).append(')');
            text.append(": ").append(value.text());
        }
        appendError(text, result.error());
        appendRuntime(text, result.runtime());
        return text.toString();
    }

    private static void appendError(StringBuilder text, ControlBridgeError error) {
        if (error == null) return;
        text.append("\nError");
        if (error.type() != null) text.append(" [").append(error.type()).append(']');
        text.append(": ").append(error.message());
    }

    private static void appendRuntime(StringBuilder text, ControlBridgeStatus runtime) {
        if (runtime == null) return;
        text.append("\nRuntime: pid=").append(runtime.pid())
                .append(" paused=").append(runtime.paused());
        if (runtime.lastHook() != null) text.append(" hook=").append(runtime.lastHook());
    }

    private static String renderEvents(ControlBridgeEventPage page) {
        if (page.events().isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        if (page.gap()) text.append("... earlier events expired ...\n");
        for (ControlBridgeEvent event : page.events()) {
            if (!text.isEmpty()) text.append('\n');
            text.append('#').append(event.sequence()).append(' ').append(event.hook());
            if (event.stepText() != null && !event.stepText().isBlank()) {
                text.append(" | ").append(event.stepText());
            } else if (event.phraseText() != null && !event.phraseText().isBlank()) {
                text.append(" | ").append(event.phraseText());
            } else if (event.signature() != null && !event.signature().isBlank()) {
                text.append(" | ").append(event.signature());
            }
        }
        if (page.hasMore()) text.append("\n... more events available ...");
        return text.toString();
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank.");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    record LiveActionResult(String output, String events) {
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

        boolean liveReady() {
            return workerRunning() && workerStatus.paused();
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
