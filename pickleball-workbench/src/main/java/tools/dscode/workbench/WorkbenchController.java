package tools.dscode.workbench;

import tools.dscode.control.protocol.*;
import tools.dscode.workbench.lease.WorkbenchControlLease;
import tools.dscode.workbench.lease.WorkbenchControlLeaseSnapshot;
import tools.dscode.workbench.lease.WorkbenchPermissionCancelledException;
import tools.dscode.workbench.lease.WorkbenchPermissionDecision;
import tools.dscode.workbench.lease.WorkbenchPermissionKind;
import tools.dscode.workbench.lease.WorkbenchPermissionRequest;
import tools.dscode.workbench.player.LiveFeatureSave;
import tools.dscode.workbench.player.LivePlaybackCoordinator;
import tools.dscode.workbench.player.LiveScenarioPlayer;
import tools.dscode.workbench.player.ScenarioOrigin;
import tools.dscode.workbench.player.WorkbenchPlayerState;
import tools.dscode.workbench.player.WorkbenchSavePreview;
import tools.dscode.workbench.player.WorkbenchSaveResult;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.sync.WorkbenchSynchronizer;
import tools.dscode.workbench.diagnostics.DiagnosticEvidenceNavigator;
import tools.dscode.workbench.terminal.WorkerLogFiles;
import tools.dscode.workbench.worker.WorkbenchLiveSession;
import tools.dscode.workbench.worker.WorkbenchWorkerStatus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Plain-Java controller shared by Workbench adapters. */
public final class WorkbenchController implements WorkbenchServices {
    private final Path projectRoot;
    private final WorkbenchSynchronizer synchronizer;
    private final WorkbenchLiveSession live;
    private final LiveScenarioPlayer player;
    private final LivePlaybackCoordinator playback;
    private final WorkbenchControlLease lease;
    private final DiagnosticEvidenceNavigator diagnostics;
    private final List<Runnable> playerListeners = new CopyOnWriteArrayList<>();

    public WorkbenchController(Path projectRoot) {
        this(projectRoot, Map.of());
    }

    public WorkbenchController(Path projectRoot, Map<String, String> workerSystemProperties) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.synchronizer = new WorkbenchSynchronizer();
        this.live = new WorkbenchLiveSession(this.projectRoot, workerSystemProperties == null ? Map.of() : workerSystemProperties);
        this.player = LiveScenarioPlayer.interactiveBuffer();
        this.playback = new LivePlaybackCoordinator(this.player);
        this.lease = new WorkbenchControlLease();
        this.diagnostics = new DiagnosticEvidenceNavigator(this.projectRoot);
    }

    @Override
    public LiveScenarioPlayer player() {
        return player;
    }

    @Override
    public LivePlaybackCoordinator playback() {
        return playback;
    }

    @Override
    public WorkbenchPlayerState playerState() {
        ScenarioOrigin origin = playback.origin();
        LiveScenarioPlayer.Line playhead = player.playheadLine().orElse(null);
        return new WorkbenchPlayerState(
                player.documentText(),
                player.lines().stream().map(LiveScenarioPlayer.Line::text).toList(),
                player.state(),
                player.playheadId().isPresent() ? player.playheadId().getAsLong() : null,
                playhead == null ? "" : playhead.text(),
                player.selectedId().isPresent() ? player.selectedId().getAsLong() : null,
                origin.file() == null ? "" : origin.file().toString(),
                origin.scenarioName(),
                origin.savable()
        );
    }

    @Override
    public WorkbenchControlLease controlLease() {
        return lease;
    }

    @Override
    public WorkbenchControlLeaseSnapshot controlLeaseSnapshot() {
        return lease.snapshot();
    }

    @Override
    public WorkbenchControlLeaseSnapshot requestControl(String agentDisplayName) {
        return lease.requestControl(agentDisplayName);
    }

    @Override
    public WorkbenchControlLeaseSnapshot releaseControl() {
        return lease.releaseControl();
    }

    @Override
    public WorkbenchControlLeaseSnapshot takeControl() {
        return lease.takeControl();
    }

    @Override
    public WorkbenchControlLeaseSnapshot setCurrentAction(String text) {
        return lease.setCurrentAction(text);
    }

    @Override
    public void answerPermission(String requestId, boolean allow) {
        lease.answerPermission(requestId, allow);
    }

    @Override
    public void attachUi() {
        lease.attachUi();
    }

    @Override
    public void detachUi() {
        lease.detachUi();
    }

    @Override
    public void addLeaseListener(Consumer<WorkbenchControlLeaseSnapshot> listener) {
        lease.addListener(listener);
    }

    @Override
    public void removeLeaseListener(Consumer<WorkbenchControlLeaseSnapshot> listener) {
        lease.removeListener(listener);
    }

    @Override
    public void addPlayerListener(Runnable listener) {
        playerListeners.add(listener);
    }

    @Override
    public void removePlayerListener(Runnable listener) {
        playerListeners.remove(listener);
    }

    @Override
    public void loadPickerScenario(
            List<String> lines,
            Path originFile,
            String scenarioName,
            int startLine,
            int endLine
    ) {
        requireMutating();
        playback.loadScenario(lines, originFile, scenarioName, startLine, endLine);
        notifyPlayer();
    }

    @Override
    public void loadDefaultDemo() {
        requireMutating();
        playback.loadDefaultDemo();
        notifyPlayer();
    }

    @Override
    public void replaceLiveDocument(List<String> lines) {
        requireMutating();
        playback.replaceFromLines(lines);
        notifyPlayer();
    }

    @Override
    public WorkbenchSavePreview savePreview() {
        return LiveFeatureSave.preview(playback);
    }

    @Override
    public WorkbenchSaveResult requestSave() {
        requireMutating();
        WorkbenchSavePreview preview = LiveFeatureSave.preview(playback);
        if (!preview.savable()) {
            return WorkbenchSaveResult.unsavable(preview.summary());
        }
        WorkbenchPermissionRequest request = new WorkbenchPermissionRequest(
                WorkbenchControlLease.newPermissionId(),
                WorkbenchPermissionKind.SAVE,
                preview.summary(),
                preview.featurePath() == null ? "" : preview.featurePath().toString(),
                preview.scenarioName()
        );
        try {
            WorkbenchPermissionDecision decision = lease.awaitPermission(request);
            if (decision != WorkbenchPermissionDecision.ALLOW) {
                return WorkbenchSaveResult.denied();
            }
            return LiveFeatureSave.write(playback);
        } catch (WorkbenchPermissionCancelledException cancelled) {
            return WorkbenchSaveResult.cancelled(cancelled.getMessage());
        }
    }

    @Override
    public WorkbenchSaveResult commitSave() {
        requireMutating();
        WorkbenchSavePreview preview = LiveFeatureSave.preview(playback);
        if (!preview.savable()) {
            return WorkbenchSaveResult.unsavable(preview.summary());
        }
        return LiveFeatureSave.write(playback);
    }

    @Override
    public WorkbenchManifest synchronize() {
        requireMutating();
        if (live.status().running()) {
            throw new IllegalStateException("Stop the Workbench worker before synchronizing the project.");
        }
        return synchronizer.sync(projectRoot);
    }

    @Override
    public WorkbenchManifest synchronizationStatus() {
        return WorkbenchManifest.read(projectRoot);
    }

    @Override
    public WorkbenchWorkerStatus startWorker() {
        return mutating(live::start);
    }

    @Override
    public WorkbenchWorkerStatus restartWorker() {
        return mutating(live::restart);
    }

    @Override
    public WorkbenchWorkerStatus stopWorker() {
        return mutating(live::stop);
    }

    @Override
    public WorkbenchWorkerStatus workerStatus() {
        return live.status();
    }

    @Override
    public Path projectRoot() {
        return projectRoot;
    }

    @Override
    public Optional<WorkerLogFiles> workerLogFiles() {
        return live.workerLogFiles();
    }

    @Override
    public ControlBridgeCallResult executeStep(String text, String argument) {
        requireMutating();
        ControlBridgeCallResult result = live.executeStep(text, argument == null ? "" : argument);
        maybeAdvancePlayhead(text, "SUCCESS".equals(result.status()));
        return result;
    }

    @Override
    public ControlBridgeValueResult mappingGet(String mapReference, String key) {
        return live.mappingGet(mapReference, key);
    }

    @Override
    public ControlBridgeValueResult mappingPut(String mapReference, String key, Object value) {
        return mutating(() -> live.mappingPut(mapReference, key, value));
    }

    @Override
    public ControlBridgeValueResult mappingResolve(String input) {
        return live.mappingResolve(input);
    }

    @Override
    public ControlBridgeMappingSnapshotResult mappingSnapshot(String mapReference) {
        return live.mappingSnapshot(mapReference);
    }

    @Override
    public ControlBridgeCallResult mappingRestore(ControlBridgeMappingSnapshot snapshot) {
        return mutating(() -> live.mappingRestore(snapshot));
    }

    @Override
    public ControlBridgeEventPage events(Long afterSequence, Integer limit) {
        return live.events(afterSequence, limit);
    }

    @Override
    public ControlBridgeBrowserPageResult browserPage() {
        return live.browserPage();
    }

    @Override
    public ControlBridgeBrowserScreenshotResult browserScreenshot() {
        return live.browserScreenshot();
    }

    @Override
    public ControlBridgeElementInspectionResult elementInspect(
            String category, String text, String operation, Integer maxElements
    ) {
        return live.elementInspect(category, text, operation, maxElements);
    }

    @Override
    public ControlBridgeServiceCallResult serviceCall(String selector) {
        return mutating(() -> live.serviceCall(selector));
    }

    @Override
    public List<ControlBridgeBreakpoint> breakpoints() {
        return live.breakpoints();
    }

    @Override
    public ControlBridgeBreakpoint addBreakpoint(
            String hook,
            String signatureContains,
            String stepContains,
            String phraseContains,
            boolean oneShot,
            Integer leaseSeconds
    ) {
        return mutating(() -> live.addBreakpoint(
                hook, signatureContains, stepContains, phraseContains, oneShot, leaseSeconds
        ));
    }

    @Override
    public boolean removeBreakpoint(String breakpointId) {
        return mutating(() -> live.removeBreakpoint(breakpointId));
    }

    @Override
    public int clearBreakpoints() {
        return mutating(live::clearBreakpoints);
    }

    @Override
    public List<ControlBridgeStepOverride> stepOverrides() {
        return live.stepOverrides();
    }

    @Override
    public ControlBridgeStepOverrideResult compileStepOverride(String id, String regex, String source) {
        return mutating(() -> live.compileStepOverride(id, regex, source));
    }

    @Override
    public boolean removeStepOverride(String id) {
        return mutating(() -> live.removeStepOverride(id));
    }

    @Override
    public int clearStepOverrides() {
        return mutating(live::clearStepOverrides);
    }

    @Override
    public Object diagnosticCatalog() {
        return diagnostics.catalogDocument();
    }

    @Override
    public Object diagnosticRun(String runId) {
        return diagnostics.runDocument(runId);
    }

    @Override
    public Object diagnosticScenarioSummary(String runId, String scenarioId) {
        return diagnostics.scenarioSummaryDocument(runId, scenarioId);
    }

    @Override
    public Object emitInvestigation(Map<String, ?> investigation) {
        try {
            return InvestigationHandoff.emit(projectRoot, investigation).sparseResult();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not emit investigation handoff.", failure);
        }
    }

    @Override
    public void close() {
        lease.detachUi();
        live.close();
    }

    private void maybeAdvancePlayhead(String text, boolean successful) {
        try {
            playback.followExecutedStep(text, successful);
        } catch (RuntimeException ignored) {
            // Playhead follow is best-effort presentation; worker execution already finished.
        }
        notifyPlayer();
    }

    private <T> T mutating(Supplier<T> action) {
        requireMutating();
        return action.get();
    }

    private void requireMutating() {
        lease.requireMutatingAccess();
    }

    private void notifyPlayer() {
        for (Runnable listener : playerListeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // Presentation listeners must not break live execution.
            }
        }
    }
}
