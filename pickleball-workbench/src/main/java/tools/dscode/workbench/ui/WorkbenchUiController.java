package tools.dscode.workbench.ui;

import tools.dscode.control.api.BoundedJsonEvidence;
import tools.dscode.control.api.ServiceCallEvidence;
import tools.dscode.control.bridge.ControlBridgeBreakpoint;
import tools.dscode.control.bridge.ControlBridgeBrowserPage;
import tools.dscode.control.bridge.ControlBridgeBrowserPageResult;
import tools.dscode.control.bridge.ControlBridgeBrowserScreenshot;
import tools.dscode.control.bridge.ControlBridgeBrowserScreenshotResult;
import tools.dscode.control.bridge.ControlBridgeCallResult;
import tools.dscode.control.bridge.ControlBridgeError;
import tools.dscode.control.bridge.ControlBridgeEvent;
import tools.dscode.control.bridge.ControlBridgeEventPage;
import tools.dscode.control.bridge.ControlBridgeServiceCallResult;
import tools.dscode.control.bridge.ControlBridgeStatus;
import tools.dscode.control.bridge.ControlBridgeStepOverride;
import tools.dscode.control.bridge.ControlBridgeStepOverrideResult;
import tools.dscode.control.bridge.ControlBridgeValue;
import tools.dscode.control.bridge.ControlBridgeValueResult;
import tools.dscode.workbench.WorkbenchServices;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.worker.WorkbenchWorkerStatus;

import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

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

    String stepOverrides() {
        return renderStepOverrides(services.stepOverrides());
    }

    ManagementResult compileStepOverride(String id, String regex, String source) {
        ControlBridgeStepOverrideResult result = services.compileStepOverride(
                required(id, "Step Override id"),
                required(regex, "Step Override regex"),
                required(source, "Step Override source")
        );
        return new ManagementResult(renderStepOverrideResult(result), stepOverrides());
    }

    ManagementResult removeStepOverride(String id) {
        boolean removed = services.removeStepOverride(required(id, "Step Override id"));
        return new ManagementResult("Removed: " + removed, stepOverrides());
    }

    ManagementResult clearStepOverrides() {
        int removed = services.clearStepOverrides();
        return new ManagementResult("Removed: " + removed, stepOverrides());
    }

    LiveActionResult browserPage() {
        ControlBridgeBrowserPageResult result = services.browserPage();
        return new LiveActionResult(renderBrowserPageResult(result), refreshEvents());
    }

    ScreenshotResult browserScreenshot() {
        ControlBridgeBrowserScreenshotResult result = services.browserScreenshot();
        byte[] png = null;
        ControlBridgeBrowserScreenshot screenshot = result.screenshot();
        if (screenshot != null && screenshot.base64() != null && !screenshot.base64().isBlank()) {
            png = Base64.getDecoder().decode(screenshot.base64());
        }
        return new ScreenshotResult(renderScreenshotResult(result), png, refreshEvents());
    }

    LiveActionResult serviceCall(String selector) {
        ControlBridgeServiceCallResult result = services.serviceCall(required(selector, "Service-call selector"));
        return new LiveActionResult(renderServiceCallResult(result), refreshEvents());
    }

    String breakpoints() {
        return renderBreakpoints(services.breakpoints());
    }

    ManagementResult addBreakpoint(
            String hook,
            String signatureContains,
            String stepContains,
            String phraseContains,
            boolean oneShot,
            String leaseSeconds
    ) {
        Integer lease = integerOrNull(leaseSeconds, "Breakpoint lease seconds");
        ControlBridgeBreakpoint breakpoint = services.addBreakpoint(
                required(hook, "Breakpoint hook"),
                blankToNull(signatureContains),
                blankToNull(stepContains),
                blankToNull(phraseContains),
                oneShot,
                lease
        );
        return new ManagementResult("Added: " + renderBreakpoint(breakpoint), breakpoints());
    }

    ManagementResult removeBreakpoint(String breakpointId) {
        boolean removed = services.removeBreakpoint(required(breakpointId, "Breakpoint id"));
        return new ManagementResult("Removed: " + removed, breakpoints());
    }

    ManagementResult clearBreakpoints() {
        int removed = services.clearBreakpoints();
        return new ManagementResult("Removed: " + removed, breakpoints());
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

    private static String renderStepOverrideResult(ControlBridgeStepOverrideResult result) {
        StringBuilder text = new StringBuilder("Status: ").append(result.status());
        if (result.override() != null) {
            text.append("\n").append(renderStepOverride(result.override()));
        }
        appendError(text, result.error());
        appendRuntime(text, result.runtime());
        return text.toString();
    }

    private static String renderStepOverrides(List<ControlBridgeStepOverride> overrides) {
        if (overrides.isEmpty()) return "No Step Overrides.";
        StringBuilder text = new StringBuilder();
        for (ControlBridgeStepOverride override : overrides) {
            if (!text.isEmpty()) text.append('\n');
            text.append(renderStepOverride(override));
        }
        return text.toString();
    }

    private static String renderStepOverride(ControlBridgeStepOverride override) {
        return override.id()
                + " | " + override.patternType()
                + " | " + override.pattern()
                + " | " + override.handlerClass();
    }

    private static String renderBrowserPageResult(ControlBridgeBrowserPageResult result) {
        StringBuilder text = new StringBuilder("Status: ").append(result.status());
        ControlBridgeBrowserPage page = result.page();
        if (page != null) {
            text.append("\nURL: ").append(page.url());
            text.append("\nTitle: ").append(page.title());
            text.append("\nWindow: ").append(page.windowHandle());
            text.append("\nWindows: ").append(page.windowHandles());
            text.append("\nViewport: ").append(page.windowWidth()).append('x').append(page.windowHeight());
            text.append("\nPage source");
            if (page.pageSourceTruncated()) text.append(" (truncated)");
            text.append(":\n").append(page.pageSource());
        }
        appendError(text, result.error());
        appendRuntime(text, result.runtime());
        return text.toString();
    }

    private static String renderScreenshotResult(ControlBridgeBrowserScreenshotResult result) {
        StringBuilder text = new StringBuilder("Status: ").append(result.status());
        ControlBridgeBrowserScreenshot screenshot = result.screenshot();
        if (screenshot != null) {
            text.append("\nScreenshot: ").append(screenshot.mimeType())
                    .append(" | ").append(screenshot.byteSize()).append(" bytes");
        }
        appendError(text, result.error());
        appendRuntime(text, result.runtime());
        return text.toString();
    }

    private static String renderServiceCallResult(ControlBridgeServiceCallResult result) {
        StringBuilder text = new StringBuilder("Status: ").append(result.status());
        ServiceCallEvidence evidence = result.evidence();
        if (evidence != null) {
            text.append("\nSelector: ").append(evidence.selector());
            text.append("\nHTTP status: ").append(evidence.statusCode());
            appendJsonEvidence(text, "Request", evidence.request());
            appendJsonEvidence(text, "Configuration", evidence.configuration());
            appendJsonEvidence(text, "Response", evidence.response());
        }
        appendError(text, result.error());
        appendRuntime(text, result.runtime());
        return text.toString();
    }

    private static void appendJsonEvidence(StringBuilder text, String label, BoundedJsonEvidence evidence) {
        if (evidence == null) return;
        text.append("\n").append(label);
        if (evidence.truncated()) text.append(" (truncated)");
        text.append(" [").append(evidence.utf8Bytes()).append(" bytes]: ")
                .append(evidence.value());
    }

    private static String renderBreakpoints(List<ControlBridgeBreakpoint> breakpoints) {
        if (breakpoints.isEmpty()) return "No breakpoints.";
        StringBuilder text = new StringBuilder();
        for (ControlBridgeBreakpoint breakpoint : breakpoints) {
            if (!text.isEmpty()) text.append('\n');
            text.append(renderBreakpoint(breakpoint));
        }
        return text.toString();
    }

    private static String renderBreakpoint(ControlBridgeBreakpoint breakpoint) {
        StringBuilder text = new StringBuilder(breakpoint.breakpointId())
                .append(" | hook=").append(breakpoint.hook())
                .append(" | oneShot=").append(breakpoint.oneShot())
                .append(" | lease=").append(breakpoint.leaseSeconds()).append('s')
                .append(" | hits=").append(breakpoint.hitCount());
        appendFilter(text, "signature", breakpoint.signatureContains());
        appendFilter(text, "step", breakpoint.stepContains());
        appendFilter(text, "phrase", breakpoint.phraseContains());
        return text.toString();
    }

    private static void appendFilter(StringBuilder text, String name, String value) {
        if (value != null && !value.isBlank()) text.append(" | ").append(name).append("~").append(value);
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
            text.append('#').append(event.sequence())
                    .append(' ').append(event.timestamp())
                    .append(' ').append(event.hook());
            if (event.stepText() != null && !event.stepText().isBlank()) {
                text.append("\n  step: ").append(event.stepText());
            }
            if (event.phraseText() != null && !event.phraseText().isBlank()) {
                text.append("\n  phrase: ").append(event.phraseText());
            }
            if (event.signature() != null && !event.signature().isBlank()) {
                text.append("\n  signature: ").append(event.signature());
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

    private static Integer integerOrNull(String value, String label) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(label + " must be an integer.");
        }
    }

    record LiveActionResult(String output, String events) {
    }

    record ManagementResult(String output, String listing) {
    }

    record ScreenshotResult(String output, byte[] png, String events) {
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
