package tools.dscode.studio.gui;

import tools.dscode.studio.build.GradleBuildService;
import tools.dscode.studio.build.MavenBuildService;
import tools.dscode.studio.language.SourceOutline;
import tools.dscode.studio.language.SourceSymbol;
import tools.dscode.studio.language.WorkspaceLanguageService;
import tools.dscode.studio.process.ManagedProcessService;
import tools.dscode.studio.process.ManagedProcessSummary;
import tools.dscode.studio.process.ProcessOutputChunk;
import tools.dscode.studio.process.WorkspaceProcessService;
import tools.dscode.studio.runtime.RuntimeBridgeDescriptor;
import tools.dscode.studio.runtime.RuntimeBridgeService;
import tools.dscode.studio.runtime.RuntimeBridgeStatus;
import tools.dscode.studio.runtime.RuntimeControlResult;
import tools.dscode.studio.runtime.RuntimeEventPage;
import tools.dscode.studio.runtime.RuntimeLaunchResult;
import tools.dscode.studio.runtime.RuntimeScenarioStatus;
import tools.dscode.studio.runtime.RuntimeValueResult;
import tools.dscode.studio.workspace.WorkspaceEntry;
import tools.dscode.studio.workspace.WorkspaceFileService;
import tools.dscode.studio.workspace.WorkspaceInfo;
import tools.dscode.studio.workspace.WorkspaceService;
import tools.dscode.studio.workspace.WorkspaceTextFile;
import tools.dscode.studio.workspace.WorkspaceWriteResult;

import java.nio.file.Path;
import java.util.List;

public final class StudioDesktopSession implements AutoCloseable {
    private final WorkspaceInfo workspace;
    private final WorkspaceFileService files;
    private final WorkspaceLanguageService language;
    private final ManagedProcessService processes;
    private final MavenBuildService maven;
    private final GradleBuildService gradle;
    private final RuntimeBridgeService runtimeBridge;

    private StudioDesktopSession(
            WorkspaceInfo workspace,
            WorkspaceFileService files,
            WorkspaceLanguageService language,
            ManagedProcessService processes,
            MavenBuildService maven,
            GradleBuildService gradle,
            RuntimeBridgeService runtimeBridge
    ) {
        this.workspace = workspace;
        this.files = files;
        this.language = language;
        this.processes = processes;
        this.maven = maven;
        this.gradle = gradle;
        this.runtimeBridge = runtimeBridge;
    }

    public static StudioDesktopSession open(Path root) {
        WorkspaceInfo workspace = new WorkspaceService().open(root);
        WorkspaceFileService files = new WorkspaceFileService(workspace.root());
        WorkspaceProcessService workspaceProcesses = new WorkspaceProcessService(workspace);
        ManagedProcessService processes = new ManagedProcessService(workspaceProcesses);
        MavenBuildService maven = new MavenBuildService(workspace, workspaceProcesses, processes);
        GradleBuildService gradle = new GradleBuildService(workspace, workspaceProcesses, processes);

        return new StudioDesktopSession(
                workspace,
                files,
                new WorkspaceLanguageService(files),
                processes,
                maven,
                gradle,
                new RuntimeBridgeService(workspace, maven, gradle)
        );
    }

    public WorkspaceInfo workspace() {
        return workspace;
    }

    public List<WorkspaceEntry> tree(int maxDepth, int maxEntries) {
        return files.tree(".", maxDepth, maxEntries);
    }

    public WorkspaceTextFile read(String path) {
        return files.readText(path);
    }

    public WorkspaceWriteResult save(String path, String content) {
        return files.writeText(path, content);
    }

    public SourceOutline outline(String path) {
        return language.outline(path);
    }

    public List<SourceSymbol> searchSymbols(String query, Integer maxResults) {
        return language.searchSymbols(query, null, null, maxResults);
    }

    public ManagedProcessSummary startTests() {
        if (workspace.gradleProject()) {
            return gradle.start(List.of("test"), null).process();
        }
        if (workspace.mavenProject()) {
            return maven.start(List.of("test"), null).process();
        }
        throw new IllegalStateException(
                "Workspace is not a Gradle or Maven project: " + workspace.root()
        );
    }

    public RuntimeLaunchResult startControlledTests() {
        return runtimeBridge.start(List.of("test"), null, true);
    }

    public RuntimeDesktopState runtimeState(
            String sessionId,
            String preferredRuntimeId
    ) {
        List<RuntimeBridgeDescriptor> runtimes = runtimeBridge.list(sessionId);
        RuntimeBridgeDescriptor selected = runtimes.stream()
                .filter(runtime -> runtime.runtimeId().equals(preferredRuntimeId))
                .findFirst()
                .orElseGet(() -> runtimes.isEmpty() ? null : runtimes.getFirst());

        if (selected == null) {
            return new RuntimeDesktopState(
                    sessionId,
                    runtimes,
                    null,
                    null,
                    List.of()
            );
        }

        RuntimeBridgeStatus status = runtimeBridge.status(sessionId, selected.runtimeId());
        List<RuntimeScenarioStatus> scenarios = runtimeBridge.scenarios(
                sessionId,
                selected.runtimeId()
        );
        return new RuntimeDesktopState(
                sessionId,
                runtimes,
                selected.runtimeId(),
                status,
                scenarios
        );
    }

    public RuntimeEventPage runtimeEvents(
            String sessionId,
            String runtimeId,
            String scenarioId,
            Long afterSequence,
            Integer limit
    ) {
        return runtimeBridge.events(
                sessionId,
                runtimeId,
                scenarioId,
                afterSequence,
                limit
        );
    }

    public RuntimeControlResult pauseRuntime(
            String sessionId,
            String runtimeId,
            String scenarioId
    ) {
        return runtimeBridge.pause(sessionId, runtimeId, scenarioId, null, null);
    }

    public RuntimeControlResult resumeRuntime(
            String sessionId,
            String runtimeId,
            String scenarioId
    ) {
        return runtimeBridge.resume(sessionId, runtimeId, scenarioId);
    }

    public RuntimeControlResult executeRuntimeStep(
            String sessionId,
            String runtimeId,
            String scenarioId,
            String text,
            String argument
    ) {
        return runtimeBridge.executeStep(
                sessionId,
                runtimeId,
                scenarioId,
                text,
                argument,
                null
        );
    }

    public RuntimeValueResult runtimeMappingGet(
            String sessionId,
            String runtimeId,
            String scenarioId,
            String mapReference,
            String key
    ) {
        return runtimeBridge.mappingGet(
                sessionId,
                runtimeId,
                scenarioId,
                mapReference,
                key,
                null
        );
    }

    public RuntimeValueResult runtimeMappingPut(
            String sessionId,
            String runtimeId,
            String scenarioId,
            String mapReference,
            String key,
            String jsonValue
    ) {
        return runtimeBridge.mappingPut(
                sessionId,
                runtimeId,
                scenarioId,
                mapReference,
                key,
                jsonValue,
                null
        );
    }

    public RuntimeValueResult runtimeMappingResolve(
            String sessionId,
            String runtimeId,
            String scenarioId,
            String input
    ) {
        return runtimeBridge.mappingResolve(
                sessionId,
                runtimeId,
                scenarioId,
                input,
                null
        );
    }

    public String testBuildTool() {
        if (workspace.gradleProject()) {
            return "Gradle";
        }
        if (workspace.mavenProject()) {
            return "Maven";
        }
        return null;
    }

    public ManagedProcessSummary processStatus(String id) {
        return processes.status(id);
    }

    public ProcessOutputChunk processOutput(
            String id,
            long stdoutOffset,
            long stderrOffset
    ) {
        return processes.output(id, stdoutOffset, stderrOffset, null);
    }

    public ManagedProcessSummary cancelProcess(String id) {
        return processes.cancel(id);
    }

    @Override
    public void close() {
        runtimeBridge.close();
        processes.close();
    }
}
