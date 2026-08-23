package tools.dscode.workbench;

import tools.dscode.control.protocol.*;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.sync.WorkbenchSynchronizer;
import tools.dscode.workbench.terminal.WorkerLogFiles;
import tools.dscode.workbench.worker.WorkbenchLiveSession;
import tools.dscode.workbench.worker.WorkbenchWorkerStatus;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Plain-Java controller shared by Workbench adapters. */
public final class WorkbenchController implements WorkbenchServices {
    private final Path projectRoot;
    private final WorkbenchSynchronizer synchronizer;
    private final WorkbenchLiveSession live;

    public WorkbenchController(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.synchronizer = new WorkbenchSynchronizer();
        this.live = new WorkbenchLiveSession(this.projectRoot);
    }

    @Override
    public WorkbenchManifest synchronize() {
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
        return live.start();
    }

    @Override
    public WorkbenchWorkerStatus restartWorker() {
        return live.restart();
    }

    @Override
    public WorkbenchWorkerStatus stopWorker() {
        return live.stop();
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
        return live.executeStep(text, argument == null ? "" : argument);
    }

    @Override
    public ControlBridgeValueResult mappingGet(String mapReference, String key) {
        return live.mappingGet(mapReference, key);
    }

    @Override
    public ControlBridgeValueResult mappingPut(String mapReference, String key, Object value) {
        return live.mappingPut(mapReference, key, value);
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
        return live.mappingRestore(snapshot);
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
        return live.serviceCall(selector);
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
        return live.addBreakpoint(
                hook, signatureContains, stepContains, phraseContains, oneShot, leaseSeconds
        );
    }

    @Override
    public boolean removeBreakpoint(String breakpointId) {
        return live.removeBreakpoint(breakpointId);
    }

    @Override
    public int clearBreakpoints() {
        return live.clearBreakpoints();
    }

    @Override
    public List<ControlBridgeStepOverride> stepOverrides() {
        return live.stepOverrides();
    }

    @Override
    public ControlBridgeStepOverrideResult compileStepOverride(String id, String regex, String source) {
        return live.compileStepOverride(id, regex, source);
    }

    @Override
    public boolean removeStepOverride(String id) {
        return live.removeStepOverride(id);
    }

    @Override
    public int clearStepOverrides() {
        return live.clearStepOverrides();
    }

    @Override
    public void close() {
        live.close();
    }
}
