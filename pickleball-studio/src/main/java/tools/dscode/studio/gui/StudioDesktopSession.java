package tools.dscode.studio.gui;

import tools.dscode.studio.build.GradleBuildService;
import tools.dscode.studio.build.MavenBuildService;
import tools.dscode.studio.collaboration.StudioActivityPage;
import tools.dscode.studio.collaboration.StudioAgentSession;
import tools.dscode.studio.collaboration.StudioClientKind;
import tools.dscode.studio.collaboration.StudioCollaborationService;
import tools.dscode.studio.collaboration.StudioEditorState;
import tools.dscode.studio.language.SourceOutline;
import tools.dscode.studio.language.SourceSymbol;
import tools.dscode.studio.language.WorkspaceLanguageService;
import tools.dscode.studio.process.ManagedProcessService;
import tools.dscode.studio.process.ManagedProcessSummary;
import tools.dscode.studio.process.ProcessOutputChunk;
import tools.dscode.studio.process.WorkspaceProcessService;
import tools.dscode.studio.runtime.*;
import tools.dscode.studio.workspace.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class StudioDesktopSession implements AutoCloseable {
    private final WorkspaceInfo workspace;
    private final WorkspaceFileService files;
    private final WorkspaceConcurrencyService concurrentFiles;
    private final WorkspaceLanguageService language;
    private final ManagedProcessService processes;
    private final MavenBuildService maven;
    private final GradleBuildService gradle;
    private final RuntimeBridgeService runtimeBridge;
    private final StudioCollaborationService collaboration;
    private final boolean ownsServices;
    private final String clientSessionId = "desktop-" + UUID.randomUUID();
    private boolean desktopActive;

    private StudioDesktopSession(
            WorkspaceInfo workspace,
            WorkspaceFileService files,
            WorkspaceConcurrencyService concurrentFiles,
            WorkspaceLanguageService language,
            ManagedProcessService processes,
            MavenBuildService maven,
            GradleBuildService gradle,
            RuntimeBridgeService runtimeBridge,
            StudioCollaborationService collaboration,
            boolean ownsServices
    ) {
        this.workspace = workspace;
        this.files = files;
        this.concurrentFiles = concurrentFiles;
        this.language = language;
        this.processes = processes;
        this.maven = maven;
        this.gradle = gradle;
        this.runtimeBridge = runtimeBridge;
        this.collaboration = collaboration;
        this.ownsServices = ownsServices;
    }

    public static StudioDesktopSession open(Path root) {
        WorkspaceInfo workspace = new WorkspaceService().open(root);
        WorkspaceFileService files = new WorkspaceFileService(workspace.root());
        WorkspaceConcurrencyService concurrentFiles = new WorkspaceConcurrencyService(files);
        WorkspaceProcessService workspaceProcesses = new WorkspaceProcessService(workspace);
        ManagedProcessService processes = new ManagedProcessService(workspaceProcesses);
        MavenBuildService maven = new MavenBuildService(workspace, workspaceProcesses, processes);
        GradleBuildService gradle = new GradleBuildService(workspace, workspaceProcesses, processes);
        StudioDesktopSession session = new StudioDesktopSession(
                workspace,
                files,
                concurrentFiles,
                new WorkspaceLanguageService(files),
                processes,
                maven,
                gradle,
                new RuntimeBridgeService(workspace, maven, gradle),
                new StudioCollaborationService(),
                true
        );
        session.activateDesktop();
        return session;
    }

    public static StudioDesktopSession shared(
            WorkspaceInfo workspace,
            WorkspaceFileService files,
            WorkspaceConcurrencyService concurrentFiles,
            WorkspaceLanguageService language,
            ManagedProcessService processes,
            MavenBuildService maven,
            GradleBuildService gradle,
            RuntimeBridgeService runtimeBridge,
            StudioCollaborationService collaboration
    ) {
        return new StudioDesktopSession(
                workspace,
                files,
                concurrentFiles,
                language,
                processes,
                maven,
                gradle,
                runtimeBridge,
                collaboration,
                false
        );
    }

    public void activateDesktop() {
        if (desktopActive) {
            return;
        }
        desktopActive = true;
        collaboration.record(
                StudioClientKind.DESKTOP,
                clientSessionId,
                "desktop.session.open",
                workspace.name(),
                workspace.root().toString()
        );
    }

    public WorkspaceInfo workspace() { return workspace; }
    public String clientSessionId() { return clientSessionId; }
    public List<WorkspaceEntry> tree(int maxDepth, int maxEntries) { return files.tree(".", maxDepth, maxEntries); }
    public WorkspaceTextFile read(String path) { return files.readText(path); }
    public WorkspaceVersionedTextFile readVersioned(String path) { return concurrentFiles.read(path); }

    public WorkspaceWriteResult save(String path, String content) {
        WorkspaceWriteResult result = files.writeText(path, content);
        collaboration.record(
                StudioClientKind.DESKTOP,
                clientSessionId,
                "workspace.write",
                result.path(),
                result.charactersWritten() + " characters"
        );
        return result;
    }

    public WorkspaceCheckedWriteResult saveChecked(String path, String expectedSha256, String content) {
        WorkspaceCheckedWriteResult result = concurrentFiles.write(path, expectedSha256, content);
        collaboration.record(
                StudioClientKind.DESKTOP,
                clientSessionId,
                result.written() ? "workspace.write" : "workspace.write.conflict",
                result.path(),
                result.message()
        );
        return result;
    }

    public StudioEditorState editorState(String path, boolean dirty, String baseSha256) {
        return collaboration.editorState(clientSessionId, path, dirty, baseSha256);
    }

    public StudioActivityPage activity(Long afterSequence, Integer limit) {
        return collaboration.activity(afterSequence, limit);
    }

    public List<StudioAgentSession> agentSessions(boolean includeInactive) {
        return collaboration.agentSessions(includeInactive);
    }

    public List<StudioEditorState> editorStates() {
        return collaboration.editorStates();
    }

    public SourceOutline outline(String path) { return language.outline(path); }
    public List<SourceSymbol> searchSymbols(String query, Integer maxResults) { return language.searchSymbols(query, null, null, maxResults); }

    public ManagedProcessSummary startTests() {
        ManagedProcessSummary process;
        if (workspace.gradleProject()) {
            process = gradle.start(List.of("test"), null).process();
        } else if (workspace.mavenProject()) {
            process = maven.start(List.of("test"), null).process();
        } else {
            throw new IllegalStateException("Workspace is not a Gradle or Maven project: " + workspace.root());
        }
        collaboration.record(
                StudioClientKind.DESKTOP,
                clientSessionId,
                "tests.start",
                process.id(),
                testBuildTool()
        );
        return process;
    }

    public RuntimeLaunchResult startControlledTests() {
        RuntimeLaunchResult result = runtimeBridge.start(List.of("test"), null, true);
        collaboration.record(
                StudioClientKind.DESKTOP,
                clientSessionId,
                "runtime.start",
                result.sessionId(),
                testBuildTool()
        );
        return result;
    }

    public RuntimeDesktopState runtimeState(String sessionId, String preferredRuntimeId) {
        List<RuntimeBridgeDescriptor> runtimes = runtimeBridge.list(sessionId);
        RuntimeBridgeDescriptor selected = runtimes.stream()
                .filter(runtime -> runtime.runtimeId().equals(preferredRuntimeId))
                .findFirst().orElseGet(() -> runtimes.isEmpty() ? null : runtimes.getFirst());
        if (selected == null) return new RuntimeDesktopState(sessionId, runtimes, null, null, List.of());
        return new RuntimeDesktopState(
                sessionId,
                runtimes,
                selected.runtimeId(),
                runtimeBridge.status(sessionId, selected.runtimeId()),
                runtimeBridge.scenarios(sessionId, selected.runtimeId())
        );
    }

    public RuntimeEventPage runtimeEvents(String sessionId, String runtimeId, String scenarioId, Long afterSequence, Integer limit) {
        return runtimeBridge.events(sessionId, runtimeId, scenarioId, afterSequence, limit);
    }

    public RuntimeControlResult pauseRuntime(String sessionId, String runtimeId, String scenarioId) {
        RuntimeControlResult result = runtimeBridge.pause(sessionId, runtimeId, scenarioId, null, null);
        recordRuntime("runtime.pause", runtimeId, scenarioId);
        return result;
    }

    public RuntimeControlResult resumeRuntime(String sessionId, String runtimeId, String scenarioId) {
        RuntimeControlResult result = runtimeBridge.resume(sessionId, runtimeId, scenarioId);
        recordRuntime("runtime.resume", runtimeId, scenarioId);
        return result;
    }

    public RuntimeControlResult executeRuntimeStep(String sessionId, String runtimeId, String scenarioId, String text, String argument) {
        RuntimeControlResult result = runtimeBridge.executeStep(sessionId, runtimeId, scenarioId, text, argument, null);
        recordRuntime("runtime.step", runtimeId, text);
        return result;
    }

    public RuntimeValueResult runtimeMappingGet(String sessionId, String runtimeId, String scenarioId, String mapReference, String key) {
        return runtimeBridge.mappingGet(sessionId, runtimeId, scenarioId, mapReference, key, null);
    }

    public RuntimeValueResult runtimeMappingPut(String sessionId, String runtimeId, String scenarioId, String mapReference, String key, String jsonValue) {
        RuntimeValueResult result = runtimeBridge.mappingPut(sessionId, runtimeId, scenarioId, mapReference, key, jsonValue, null);
        recordRuntime("runtime.mapping.put", mapReference, key);
        return result;
    }

    public RuntimeValueResult runtimeMappingResolve(String sessionId, String runtimeId, String scenarioId, String input) {
        return runtimeBridge.mappingResolve(sessionId, runtimeId, scenarioId, input, null);
    }

    public RuntimeMappingSnapshotResult runtimeMappingSnapshot(String sessionId, String runtimeId, String scenarioId, String mapReference) {
        return runtimeBridge.mappingSnapshot(sessionId, runtimeId, scenarioId, mapReference, null);
    }

    public List<RuntimeMappingSnapshotSummary> runtimeMappingSnapshots(String sessionId, String runtimeId, String scenarioId) {
        return runtimeBridge.mappingSnapshots(sessionId, runtimeId, scenarioId, null);
    }

    public RuntimeControlResult runtimeMappingRestore(String sessionId, String snapshotId) {
        RuntimeControlResult result = runtimeBridge.mappingRestore(sessionId, snapshotId, null);
        recordRuntime("runtime.mapping.restore", snapshotId, "");
        return result;
    }

    public RuntimeElementInspectionResult runtimeElementInspect(
            String sessionId, String runtimeId, String scenarioId,
            String category, String text, String operation, Integer maxElements
    ) {
        return runtimeBridge.elementInspect(
                sessionId, runtimeId, scenarioId, category, text, operation, maxElements, null
        );
    }

    public RuntimeServiceCallResult runtimeServiceCall(
            String sessionId, String runtimeId, String scenarioId, String selector
    ) {
        RuntimeServiceCallResult result = runtimeBridge.serviceCall(sessionId, runtimeId, scenarioId, selector, null);
        recordRuntime("runtime.service.call", runtimeId, selector);
        return result;
    }

    public RuntimeBreakpoint runtimeBreakpointAdd(
            String sessionId, String runtimeId, String scenarioId, String hook,
            String signatureContains, String stepContains, String phraseContains,
            boolean oneShot, Integer leaseSeconds
    ) {
        RuntimeBreakpoint result = runtimeBridge.addBreakpoint(
                sessionId, runtimeId, scenarioId, hook, signatureContains,
                stepContains, phraseContains, oneShot, leaseSeconds
        );
        recordRuntime("runtime.breakpoint.add", result.breakpointId(), hook);
        return result;
    }

    public List<RuntimeBreakpoint> runtimeBreakpoints(String sessionId, String runtimeId) {
        return runtimeBridge.breakpoints(sessionId, runtimeId);
    }

    public boolean runtimeBreakpointRemove(String sessionId, String runtimeId, String breakpointId) {
        boolean removed = runtimeBridge.removeBreakpoint(sessionId, runtimeId, breakpointId);
        if (removed) {
            recordRuntime("runtime.breakpoint.remove", breakpointId, "");
        }
        return removed;
    }

    public int runtimeBreakpointsClear(String sessionId, String runtimeId) {
        int cleared = runtimeBridge.clearBreakpoints(sessionId, runtimeId);
        recordRuntime("runtime.breakpoint.clear", runtimeId, String.valueOf(cleared));
        return cleared;
    }

    public String testBuildTool() {
        if (workspace.gradleProject()) return "Gradle";
        if (workspace.mavenProject()) return "Maven";
        return null;
    }

    public ManagedProcessSummary processStatus(String id) { return processes.status(id); }

    public ProcessOutputChunk processOutput(String id, long stdoutOffset, long stderrOffset) {
        return processes.output(id, stdoutOffset, stderrOffset, null);
    }

    public ManagedProcessSummary cancelProcess(String id) {
        ManagedProcessSummary result = processes.cancel(id);
        collaboration.record(
                StudioClientKind.DESKTOP,
                clientSessionId,
                "process.cancel",
                id,
                result.state().toString()
        );
        return result;
    }

    private void recordRuntime(String operation, String target, String detail) {
        collaboration.record(
                StudioClientKind.DESKTOP,
                clientSessionId,
                operation,
                target,
                detail
        );
    }

    @Override
    public void close() {
        if (desktopActive) {
            collaboration.closeClient(clientSessionId);
            desktopActive = false;
        }
        if (ownsServices) {
            runtimeBridge.close();
            processes.close();
        }
    }
}
