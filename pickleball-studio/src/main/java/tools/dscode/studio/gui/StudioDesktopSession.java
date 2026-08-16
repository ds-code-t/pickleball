
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

    private StudioDesktopSession(
            WorkspaceInfo workspace,
            WorkspaceFileService files,
            WorkspaceLanguageService language,
            ManagedProcessService processes,
            MavenBuildService maven,
            GradleBuildService gradle
    ) {
        this.workspace = workspace;
        this.files = files;
        this.language = language;
        this.processes = processes;
        this.maven = maven;
        this.gradle = gradle;
    }

    public static StudioDesktopSession open(Path root) {
        WorkspaceInfo workspace = new WorkspaceService().open(root);
        WorkspaceFileService files = new WorkspaceFileService(workspace.root());
        WorkspaceProcessService workspaceProcesses = new WorkspaceProcessService(workspace);
        ManagedProcessService processes = new ManagedProcessService(workspaceProcesses);

        return new StudioDesktopSession(
                workspace,
                files,
                new WorkspaceLanguageService(files),
                processes,
                new MavenBuildService(workspace, workspaceProcesses, processes),
                new GradleBuildService(workspace, workspaceProcesses, processes)
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
        processes.close();
    }
}
