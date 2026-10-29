package tools.dscode.workbench.worker;

import tools.dscode.control.protocol.*;
import tools.dscode.workbench.bridge.ControlBridgeClient;
import tools.dscode.workbench.terminal.WorkerLogFiles;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Scenario-bound live execution facade over one persistent Workbench worker.
 * Every command must leave the same worker/runtime/scenario paused and available.
 */
public final class WorkbenchLiveSession implements AutoCloseable {
    private static final int COMMAND_TIMEOUT_SECONDS = 60;

    private final Path projectRoot;
    private final Map<String, String> workerProperties;
    private final WorkbenchWorkerManager workers;
    private final LiveSessionPublication publication;

    public WorkbenchLiveSession(Path projectRoot) {
        this(projectRoot, Map.of());
    }

    public WorkbenchLiveSession(Path projectRoot, Map<String, String> workerSystemProperties) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.workerProperties = workerSystemProperties == null ? Map.of() : Map.copyOf(workerSystemProperties);
        this.workers = new WorkbenchWorkerManager(this.projectRoot, this.workerProperties);
        this.publication = new LiveSessionPublication(this.projectRoot, this.workerProperties);
    }

    /** Run id the hold gate reads. Blank until the live session is open, unless one was given. */
    public String sessionRunId() {
        if (publication.runId() != null) return publication.runId();
        String id = workerProperties.get("pkb_run_id");
        if (id == null || id.isBlank()) return null;
        return id.trim();
    }

    /**
     * Reflects playback on the descriptor when this session has a run id.
     * A hold is left in place.
     */
    public void noteSessionState(SessionDescriptor.State state) {
        if (publication.runId() != null) {
            publication.playback(state);
            return;
        }
        String runId = sessionRunId();
        if (runId == null || state == null) return;
        Optional<SessionDescriptor> current = SessionDescriptorWriter.read(projectRoot, runId);
        if (current.isEmpty()) return;
        if (current.get().state() == SessionDescriptor.State.HELD && state != SessionDescriptor.State.HELD) return;
        if (!current.get().state().allows(state)) return;
        SessionDescriptorWriter.transition(projectRoot, runId, state, Instant.now());
    }

    public WorkbenchWorkerStatus start() {
        WorkbenchWorkerStatus status = workers.startInteractive();
        publication.opened(status.sessionId());
        return status;
    }

    public WorkbenchWorkerStatus restart() {
        publication.closing();
        WorkbenchWorkerStatus status = workers.restartInteractive();
        publication.opened(status.sessionId());
        return status;
    }

    public WorkbenchWorkerStatus status() {
        return workers.status();
    }

    public Optional<WorkerLogFiles> workerLogFiles() {
        return workers.workerLogFiles();
    }

    public WorkbenchWorkerStatus stop() {
        publication.closing();
        return workers.stop();
    }

    public ControlBridgeCallResult executeStep(String text) {
        return executeStep(text, "");
    }

    public ControlBridgeCallResult executeStep(String text, String argument) {
        publication.beforeStep();
        try {
            return call(binding -> binding.client().executeStep(
                    binding.scenarioId(), text, argument, COMMAND_TIMEOUT_SECONDS
            ));
        } finally {
            publication.afterStep(currentPage());
        }
    }

    public ControlBridgeStepResolution resolveStep(String text, String argument) {
        return call(binding -> binding.client().resolveStep(
                binding.scenarioId(), text, argument, COMMAND_TIMEOUT_SECONDS
        ));
    }

    public ControlBridgeValueResult mappingGet(String mapReference, String key) {
        return call(binding -> binding.client().mappingGet(
                binding.scenarioId(), mapReference, key, COMMAND_TIMEOUT_SECONDS
        ));
    }

    public ControlBridgeValueResult mappingPut(String mapReference, String key, Object value) {
        return call(binding -> binding.client().mappingPut(
                binding.scenarioId(), mapReference, key, value, COMMAND_TIMEOUT_SECONDS
        ));
    }

    public ControlBridgeValueResult mappingResolve(String input) {
        return call(binding -> binding.client().mappingResolve(
                binding.scenarioId(), input, COMMAND_TIMEOUT_SECONDS
        ));
    }

    public ControlBridgeMappingSnapshotResult mappingSnapshot(String mapReference) {
        return call(binding -> binding.client().mappingSnapshot(
                binding.scenarioId(), mapReference, COMMAND_TIMEOUT_SECONDS
        ));
    }

    public ControlBridgeCallResult mappingRestore(ControlBridgeMappingSnapshot snapshot) {
        return call(binding -> binding.client().mappingRestore(
                binding.scenarioId(), snapshot, COMMAND_TIMEOUT_SECONDS
        ));
    }

    public ControlBridgeBrowserPageResult browserPage() {
        return call(binding -> binding.client().browserPage(
                binding.scenarioId(), COMMAND_TIMEOUT_SECONDS
        ));
    }

    public ControlBridgeBrowserScreenshotResult browserScreenshot() {
        return call(binding -> binding.client().browserScreenshot(
                binding.scenarioId(), COMMAND_TIMEOUT_SECONDS
        ));
    }

    public ControlBridgeElementInspectionResult elementInspect(
            String category, String text, String operation, Integer maxElements
    ) {
        return call(binding -> binding.client().elementInspect(
                binding.scenarioId(), category, text, operation, maxElements,
                COMMAND_TIMEOUT_SECONDS
        ));
    }

    public ControlBridgePageQueryResult pageQuery(String category, String text) {
        return call(binding -> binding.client().pageQuery(
                binding.scenarioId(), category, text, COMMAND_TIMEOUT_SECONDS
        ));
    }

    public ControlBridgeServiceCallResult serviceCall(String selector) {
        return call(binding -> binding.client().serviceCall(
                binding.scenarioId(), selector, COMMAND_TIMEOUT_SECONDS
        ));
    }

    public ControlBridgeEventPage events(Long afterSequence, Integer limit) {
        return call(binding -> binding.client().events(
                binding.scenarioId(), afterSequence, limit
        ));
    }

    public List<ControlBridgeBreakpoint> breakpoints() {
        return call(binding -> binding.client().breakpoints());
    }

    public ControlBridgeBreakpoint addBreakpoint(
            String hook,
            String signatureContains,
            String stepContains,
            String phraseContains,
            boolean oneShot,
            Integer leaseSeconds
    ) {
        return call(binding -> binding.client().addBreakpoint(
                binding.scenarioId(), hook, signatureContains, stepContains,
                phraseContains, oneShot, leaseSeconds
        ));
    }

    public boolean removeBreakpoint(String breakpointId) {
        return call(binding -> binding.client().removeBreakpoint(breakpointId));
    }

    public int clearBreakpoints() {
        return call(binding -> binding.client().clearBreakpoints());
    }

    public List<ControlBridgeStepOverride> stepOverrides() {
        return call(binding -> binding.client().stepOverrides(binding.scenarioId()));
    }

    /**
     * Compiles and installs a REPLACE-mode REGEX Step Override into the active worker.
     * Source must contain {@code {{CLASS_NAME}}} as the generated class-name token.
     */
    public ControlBridgeStepOverrideResult compileStepOverride(
            String id,
            String regex,
            String source
    ) {
        return call(binding -> binding.client().compileStepOverride(
                binding.scenarioId(), id, regex, source, COMMAND_TIMEOUT_SECONDS
        ));
    }

    public boolean removeStepOverride(String id) {
        return call(binding -> binding.client().removeStepOverride(
                binding.scenarioId(), id
        ));
    }

    public int clearStepOverrides() {
        return call(binding -> binding.client().clearStepOverrides(
                binding.scenarioId()
        ));
    }

    @Override
    public void close() {
        publication.closing();
        workers.close();
    }

    private LiveSessionPublication.Page currentPage() {
        try {
            ControlBridgeBrowserPageResult result = browserPage();
            if (result == null || result.page() == null || !"SUCCESS".equals(result.status())) {
                return LiveSessionPublication.Page.none();
            }
            ControlBridgeBrowserPage page = result.page();
            return new LiveSessionPublication.Page(
                    page.browserType(),
                    page.cdpDebuggerAddress(),
                    page.url(),
                    page.windowHandles()
            );
        } catch (RuntimeException failure) {
            return LiveSessionPublication.Page.none();
        }
    }

    private <T> T call(Function<Binding, T> operation) {
        Binding before = binding();
        T result = operation.apply(before);
        WorkbenchWorkerStatus after = requirePaused(workers.status());
        if (!Objects.equals(before.pid(), after.pid())
                || !Objects.equals(before.runtimeId(), after.runtimeId())
                || !Objects.equals(before.scenarioId(), after.scenarioId())) {
            throw new IllegalStateException(
                    "Live Workbench operation changed the active worker context."
            );
        }
        return result;
    }

    private Binding binding() {
        WorkbenchWorkerStatus status = requirePaused(workers.status());
        String scenarioId = workers.activeScenarioId();
        if (!Objects.equals(status.scenarioId(), scenarioId)) {
            throw new IllegalStateException(
                    "Workbench worker status and active bridge scenario do not match."
            );
        }
        return new Binding(
                workers.activeClient(), scenarioId, status.pid(), status.runtimeId()
        );
    }

    private static WorkbenchWorkerStatus requirePaused(WorkbenchWorkerStatus status) {
        if (!status.running() || !status.paused()
                || status.pid() == null
                || status.runtimeId() == null
                || status.scenarioId() == null) {
            throw new IllegalStateException(
                    "Workbench live operations require a paused interactive worker."
            );
        }
        return status;
    }

    private record Binding(
            ControlBridgeClient client,
            String scenarioId,
            Long pid,
            String runtimeId
    ) {}
}
