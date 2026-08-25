package tools.dscode.workbench;

import tools.dscode.control.protocol.ControlBridgeBreakpoint;
import tools.dscode.control.protocol.ControlBridgeBrowserPageResult;
import tools.dscode.control.protocol.ControlBridgeBrowserScreenshotResult;
import tools.dscode.control.protocol.ControlBridgeCallResult;
import tools.dscode.control.protocol.ControlBridgeElementInspectionResult;
import tools.dscode.control.protocol.ControlBridgeEventPage;
import tools.dscode.control.protocol.ControlBridgeMappingSnapshot;
import tools.dscode.control.protocol.ControlBridgeMappingSnapshotResult;
import tools.dscode.control.protocol.ControlBridgeServiceCallResult;
import tools.dscode.control.protocol.ControlBridgeStepOverride;
import tools.dscode.control.protocol.ControlBridgeStepOverrideResult;
import tools.dscode.control.protocol.ControlBridgeValueResult;
import tools.dscode.workbench.lease.WorkbenchControlLease;
import tools.dscode.workbench.lease.WorkbenchControlLeaseSnapshot;
import tools.dscode.workbench.player.LivePlaybackCoordinator;
import tools.dscode.workbench.player.LiveScenarioPlayer;
import tools.dscode.workbench.player.WorkbenchPlayerState;
import tools.dscode.workbench.player.WorkbenchSavePreview;
import tools.dscode.workbench.player.WorkbenchSaveResult;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.terminal.WorkerLogFiles;
import tools.dscode.workbench.worker.WorkbenchWorkerStatus;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Shared Workbench controller surface used by protocol and presentation adapters. */
public interface WorkbenchServices extends AutoCloseable {
    LiveScenarioPlayer player();

    LivePlaybackCoordinator playback();

    WorkbenchPlayerState playerState();

    WorkbenchControlLease controlLease();

    WorkbenchControlLeaseSnapshot controlLeaseSnapshot();

    WorkbenchControlLeaseSnapshot requestControl(String agentDisplayName);

    WorkbenchControlLeaseSnapshot releaseControl();

    WorkbenchControlLeaseSnapshot takeControl();

    WorkbenchControlLeaseSnapshot setCurrentAction(String text);

    void answerPermission(String requestId, boolean allow);

    void attachUi();

    void detachUi();

    void addLeaseListener(Consumer<WorkbenchControlLeaseSnapshot> listener);

    void removeLeaseListener(Consumer<WorkbenchControlLeaseSnapshot> listener);

    void addPlayerListener(Runnable listener);

    void removePlayerListener(Runnable listener);

    void loadPickerScenario(
            List<String> lines,
            Path originFile,
            String scenarioName,
            int startLine,
            int endLine
    );

    void loadDefaultDemo();

    void replaceLiveDocument(List<String> lines);

    WorkbenchSavePreview savePreview();

    WorkbenchSaveResult requestSave();

    WorkbenchSaveResult commitSave();

    WorkbenchManifest synchronize();

    WorkbenchManifest synchronizationStatus();

    WorkbenchWorkerStatus startWorker();

    WorkbenchWorkerStatus restartWorker();

    WorkbenchWorkerStatus stopWorker();

    WorkbenchWorkerStatus workerStatus();

    Path projectRoot();

    Optional<WorkerLogFiles> workerLogFiles();

    ControlBridgeCallResult executeStep(String text, String argument);

    ControlBridgeValueResult mappingGet(String mapReference, String key);

    ControlBridgeValueResult mappingPut(String mapReference, String key, Object value);

    ControlBridgeValueResult mappingResolve(String input);

    ControlBridgeMappingSnapshotResult mappingSnapshot(String mapReference);

    ControlBridgeCallResult mappingRestore(ControlBridgeMappingSnapshot snapshot);

    ControlBridgeEventPage events(Long afterSequence, Integer limit);

    ControlBridgeBrowserPageResult browserPage();

    ControlBridgeBrowserScreenshotResult browserScreenshot();

    ControlBridgeElementInspectionResult elementInspect(
            String category, String text, String operation, Integer maxElements
    );

    ControlBridgeServiceCallResult serviceCall(String selector);

    List<ControlBridgeBreakpoint> breakpoints();

    ControlBridgeBreakpoint addBreakpoint(
            String hook,
            String signatureContains,
            String stepContains,
            String phraseContains,
            boolean oneShot,
            Integer leaseSeconds
    );

    boolean removeBreakpoint(String breakpointId);

    int clearBreakpoints();

    List<ControlBridgeStepOverride> stepOverrides();

    ControlBridgeStepOverrideResult compileStepOverride(String id, String regex, String source);

    boolean removeStepOverride(String id);

    int clearStepOverrides();

    Object diagnosticCatalog();

    Object diagnosticRun(String runId);

    Object diagnosticScenarioSummary(String runId, String scenarioId);

    Object emitInvestigation(Map<String, ?> investigation);

    @Override
    void close();
}
