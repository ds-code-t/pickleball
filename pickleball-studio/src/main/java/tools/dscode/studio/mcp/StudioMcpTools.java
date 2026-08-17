package tools.dscode.studio.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.dscode.studio.build.GradleBuildService;
import tools.dscode.studio.build.GradleRunResult;
import tools.dscode.studio.build.ManagedGradleRunResult;
import tools.dscode.studio.build.ManagedMavenRunResult;
import tools.dscode.studio.build.MavenBuildService;
import tools.dscode.studio.build.MavenRunResult;
import tools.dscode.studio.gradle.GradleProjectModelService;
import tools.dscode.studio.gradle.GradleTaskInfo;
import tools.dscode.studio.gradle.GradleWorkspaceModel;
import tools.dscode.studio.language.SourceOutline;
import tools.dscode.studio.language.SourceSymbol;
import tools.dscode.studio.language.WorkspaceLanguageService;
import tools.dscode.studio.process.ManagedProcessService;
import tools.dscode.studio.process.ManagedProcessSummary;
import tools.dscode.studio.process.ProcessOutputChunk;
import tools.dscode.studio.process.ProcessResult;
import tools.dscode.studio.process.WorkspaceProcessService;
import tools.dscode.studio.runtime.RuntimeBridgeDescriptor;
import tools.dscode.studio.runtime.RuntimeBridgeService;
import tools.dscode.studio.runtime.RuntimeBridgeStatus;
import tools.dscode.studio.runtime.RuntimeControlResult;
import tools.dscode.studio.runtime.RuntimeLaunchResult;
import tools.dscode.studio.workspace.TextSearchMatch;
import tools.dscode.studio.workspace.WorkspaceEntry;
import tools.dscode.studio.workspace.WorkspaceFileService;
import tools.dscode.studio.workspace.WorkspaceInfo;
import tools.dscode.studio.workspace.WorkspaceTextFile;
import tools.dscode.studio.workspace.WorkspaceWriteResult;

import java.util.List;

public final class StudioMcpTools {
    private static final int DEFAULT_TREE_DEPTH = 4;
    private static final int DEFAULT_TREE_ENTRIES = 500;
    private static final int DEFAULT_SEARCH_RESULTS = 100;

    private final WorkspaceInfo workspace;
    private final WorkspaceFileService files;
    private final WorkspaceProcessService processes;
    private final ManagedProcessService managedProcesses;
    private final MavenBuildService maven;
    private final GradleBuildService gradle;
    private final GradleProjectModelService gradleModel;
    private final WorkspaceLanguageService language;
    private final RuntimeBridgeService runtimeBridge;

    public StudioMcpTools(
            WorkspaceInfo workspace,
            WorkspaceFileService files,
            WorkspaceProcessService processes,
            ManagedProcessService managedProcesses,
            MavenBuildService maven,
            GradleBuildService gradle,
            GradleProjectModelService gradleModel,
            WorkspaceLanguageService language,
            RuntimeBridgeService runtimeBridge
    ) {
        this.workspace = workspace;
        this.files = files;
        this.processes = processes;
        this.managedProcesses = managedProcesses;
        this.maven = maven;
        this.gradle = gradle;
        this.gradleModel = gradleModel;
        this.language = language;
        this.runtimeBridge = runtimeBridge;
    }

    @Tool(
            name = "workspace_status",
            description = "Return the currently opened Studio workspace and its Maven, Gradle, and Git markers."
    )
    public StudioWorkspaceStatus workspaceStatus() {
        return new StudioWorkspaceStatus(
                workspace.root().toString(),
                workspace.name(),
                workspace.mavenProject(),
                workspace.gradleProject(),
                workspace.gitRepository()
        );
    }

    @Tool(
            name = "workspace_tree",
            description = "List a deterministic workspace directory tree. Generated/build directories are skipped."
    )
    public List<WorkspaceEntry> workspaceTree(
            @ToolParam(description = "Workspace-relative directory path. Empty means the workspace root.", required = false)
            String path,
            @ToolParam(description = "Maximum directory depth. Defaults to 4.", required = false)
            Integer maxDepth,
            @ToolParam(description = "Maximum returned entries. Defaults to 500.", required = false)
            Integer maxEntries
    ) {
        return files.tree(
                path,
                maxDepth == null ? DEFAULT_TREE_DEPTH : maxDepth,
                maxEntries == null ? DEFAULT_TREE_ENTRIES : maxEntries
        );
    }

    @Tool(
            name = "workspace_read_file",
            description = "Read one UTF-8 text file from the current workspace."
    )
    public WorkspaceTextFile readFile(
            @ToolParam(description = "Workspace-relative file path.") String path
    ) {
        return files.readText(path);
    }

    @Tool(
            name = "workspace_write_file",
            description = "Create or replace one UTF-8 text file inside the current workspace. Parent directories are created as needed."
    )
    public WorkspaceWriteResult writeFile(
            @ToolParam(description = "Workspace-relative file path.") String path,
            @ToolParam(description = "Complete UTF-8 text content to write.") String content
    ) {
        return files.writeText(path, content);
    }

    @Tool(
            name = "workspace_search_text",
            description = "Search UTF-8 workspace text files for a literal substring. Generated/build directories are skipped."
    )
    public List<TextSearchMatch> searchText(
            @ToolParam(description = "Literal text to search for.") String query,
            @ToolParam(description = "Workspace-relative file or directory to search. Empty means the workspace root.", required = false)
            String path,
            @ToolParam(description = "Whether matching is case-sensitive. Defaults to true.", required = false)
            Boolean caseSensitive,
            @ToolParam(description = "Maximum returned matches. Defaults to 100.", required = false)
            Integer maxResults
    ) {
        return files.searchText(
                query,
                path,
                caseSensitive == null || caseSensitive,
                maxResults == null ? DEFAULT_SEARCH_RESULTS : maxResults
        );
    }

    @Tool(
            name = "process_run",
            description = "Run one non-interactive process in the current workspace and return its exit code and captured output."
    )
    public ProcessResult runProcess(
            @ToolParam(description = "Executable and arguments as an argv list.") List<String> command,
            @ToolParam(description = "Workspace-relative working directory. Empty means the workspace root.", required = false)
            String workingDirectory,
            @ToolParam(description = "Timeout in seconds. Defaults to 120.", required = false)
            Integer timeoutSeconds
    ) {
        return processes.run(command, workingDirectory, timeoutSeconds);
    }

    @Tool(
            name = "process_start",
            description = "Start a managed non-interactive process and return immediately with a Studio process id."
    )
    public ManagedProcessSummary startProcess(
            @ToolParam(description = "Executable and arguments as an argv list.") List<String> command,
            @ToolParam(description = "Workspace-relative working directory. Empty means the workspace root.", required = false)
            String workingDirectory,
            @ToolParam(description = "Timeout in seconds. Defaults to 120.", required = false)
            Integer timeoutSeconds
    ) {
        return managedProcesses.start(command, workingDirectory, timeoutSeconds);
    }

    @Tool(
            name = "process_list",
            description = "List recent managed Studio processes newest first."
    )
    public List<ManagedProcessSummary> listProcesses(
            @ToolParam(description = "Maximum runs to return. Defaults to 20; maximum 100.", required = false)
            Integer limit
    ) {
        return managedProcesses.list(limit);
    }

    @Tool(
            name = "process_status",
            description = "Return current state and metadata for one managed Studio process."
    )
    public ManagedProcessSummary processStatus(
            @ToolParam(description = "Studio process id returned by process_start, maven_start, gradle_start, or runtime_start.") String id
    ) {
        return managedProcesses.status(id);
    }

    @Tool(
            name = "process_output",
            description = "Read incremental stdout/stderr for a managed process using independent output cursors."
    )
    public ProcessOutputChunk processOutput(
            @ToolParam(description = "Studio process id.") String id,
            @ToolParam(description = "Next stdout character offset. Defaults to 0.", required = false)
            Long stdoutOffset,
            @ToolParam(description = "Next stderr character offset. Defaults to 0.", required = false)
            Long stderrOffset,
            @ToolParam(description = "Maximum characters returned per stream. Defaults to 65536; maximum 262144.", required = false)
            Integer maxChars
    ) {
        return managedProcesses.output(id, stdoutOffset, stderrOffset, maxChars);
    }

    @Tool(
            name = "process_cancel",
            description = "Cancel a running managed Studio process and its owned descendant processes."
    )
    public ManagedProcessSummary cancelProcess(
            @ToolParam(description = "Studio process id.") String id
    ) {
        return managedProcesses.cancel(id);
    }

    @Tool(
            name = "maven_run",
            description = "Run Maven 3.9.16 against the current Maven workspace using Studio's bundled Maven runtime. No host Maven installation is required."
    )
    public MavenRunResult runMaven(
            @ToolParam(description = "Maven goals and CLI arguments, for example [\"test\"] or [\"-q\", \"test\"].")
            List<String> arguments,
            @ToolParam(description = "Timeout in seconds. Defaults to 600.", required = false)
            Integer timeoutSeconds
    ) {
        return maven.run(arguments, timeoutSeconds);
    }

    @Tool(
            name = "maven_start",
            description = "Start Maven 3.9.16 as a managed Studio process and return immediately with a process id."
    )
    public ManagedMavenRunResult startMaven(
            @ToolParam(description = "Maven goals and CLI arguments, for example [\"test\"] or [\"-q\", \"test\"].")
            List<String> arguments,
            @ToolParam(description = "Timeout in seconds. Defaults to 600.", required = false)
            Integer timeoutSeconds
    ) {
        return maven.start(arguments, timeoutSeconds);
    }

    @Tool(
            name = "gradle_run",
            description = "Run the current Gradle workspace through its checked-in Gradle Wrapper. No host Gradle installation is required."
    )
    public GradleRunResult runGradle(
            @ToolParam(description = "Gradle tasks and CLI arguments, for example [\"test\"] or [\"help\", \"--warning-mode=all\"].")
            List<String> arguments,
            @ToolParam(description = "Timeout in seconds. Defaults to 600.", required = false)
            Integer timeoutSeconds
    ) {
        return gradle.run(arguments, timeoutSeconds);
    }

    @Tool(
            name = "gradle_start",
            description = "Start the current Gradle workspace through its checked-in Gradle Wrapper as a managed Studio process."
    )
    public ManagedGradleRunResult startGradle(
            @ToolParam(description = "Gradle tasks and CLI arguments, for example [\"test\"] or [\"help\", \"--warning-mode=all\"].")
            List<String> arguments,
            @ToolParam(description = "Timeout in seconds. Defaults to 600.", required = false)
            Integer timeoutSeconds
    ) {
        return gradle.start(arguments, timeoutSeconds);
    }

    @Tool(
            name = "gradle_model",
            description = "Read the Gradle Tooling API project model, including Gradle/JVM environment, project hierarchy, and source/resource roots without resolving external dependencies."
    )
    public GradleWorkspaceModel gradleModel() {
        return gradleModel.model();
    }

    @Tool(
            name = "gradle_tasks",
            description = "List deterministic Gradle Tooling API task metadata for one project path. Empty project path means the root project."
    )
    public List<GradleTaskInfo> gradleTasks(
            @ToolParam(description = "Gradle project path such as ':' or ':app'. Empty means ':'.", required = false)
            String projectPath
    ) {
        return gradleModel.tasks(projectPath);
    }

    @Tool(
            name = "source_outline",
            description = "Parse one Java or Gherkin source file and return its definition outline plus syntax diagnostics."
    )
    public SourceOutline sourceOutline(
            @ToolParam(description = "Workspace-relative .java or .feature file path.") String path
    ) {
        return language.outline(path);
    }

    @Tool(
            name = "symbol_search",
            description = "Search parsed Java and Gherkin definitions across the workspace. Generated/build directories are skipped."
    )
    public List<SourceSymbol> symbolSearch(
            @ToolParam(description = "Case-insensitive text matched against symbol name, qualified name, or container.") String query,
            @ToolParam(description = "Optional language filter: JAVA or GHERKIN.", required = false) String sourceLanguage,
            @ToolParam(description = "Optional SourceSymbolKind names to include.", required = false) List<String> kinds,
            @ToolParam(description = "Maximum returned symbols. Defaults to 100; maximum 500.", required = false) Integer maxResults
    ) {
        return language.searchSymbols(query, sourceLanguage, kinds, maxResults);
    }

    @Tool(
            name = "symbol_definitions",
            description = "Find exact Java or Gherkin definitions by simple or qualified symbol name."
    )
    public List<SourceSymbol> symbolDefinitions(
            @ToolParam(description = "Exact simple or qualified symbol name.") String name,
            @ToolParam(description = "Optional language filter: JAVA or GHERKIN.", required = false) String sourceLanguage,
            @ToolParam(description = "Optional SourceSymbolKind names to include.", required = false) List<String> kinds,
            @ToolParam(description = "Maximum returned definitions. Defaults to 100; maximum 500.", required = false) Integer maxResults
    ) {
        return language.findDefinitions(name, sourceLanguage, kinds, maxResults);
    }


    @Tool(
            name = "runtime_start",
            description = "Start an opt-in Pickleball test run with the private Studio runtime bridge enabled. The build is managed by Studio; bridge control is not enabled for ordinary maven_start or gradle_start calls."
    )
    public RuntimeLaunchResult runtimeStart(
            @ToolParam(description = "Build goals/tasks and options. Empty defaults to [\"test\"].", required = false)
            List<String> buildArguments,
            @ToolParam(description = "Managed build timeout in seconds. Defaults to 3600.", required = false)
            Integer timeoutSeconds,
            @ToolParam(description = "Pause the first Pickleball scenario at its first semantic control hook. Defaults to true.", required = false)
            Boolean pauseFirstScenario
    ) {
        return runtimeBridge.start(buildArguments, timeoutSeconds, pauseFirstScenario);
    }

    @Tool(
            name = "runtime_list",
            description = "List live consumer test JVMs that have published a private runtime bridge descriptor for one Studio runtime session."
    )
    public List<RuntimeBridgeDescriptor> runtimeList(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId
    ) {
        return runtimeBridge.list(sessionId);
    }

    @Tool(
            name = "runtime_status",
            description = "Read live Pickleball scenario/control state from one consumer test JVM."
    )
    public RuntimeBridgeStatus runtimeStatus(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId
    ) {
        return runtimeBridge.status(sessionId, runtimeId);
    }

    @Tool(
            name = "runtime_pause",
            description = "Request a live Pickleball runtime to pause at a semantic control hook. A finite pause lease prevents an abandoned controller from blocking the scenario indefinitely."
    )
    public RuntimeControlResult runtimePause(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId,
            @ToolParam(description = "Seconds to wait for a pausable hook. Defaults to 30; maximum 600.", required = false)
            Integer waitSeconds,
            @ToolParam(description = "Maximum pause lease in seconds. Defaults to 120; maximum 3600.", required = false)
            Integer leaseSeconds
    ) {
        return runtimeBridge.pause(sessionId, runtimeId, waitSeconds, leaseSeconds);
    }

    @Tool(
            name = "runtime_resume",
            description = "Resume a paused Pickleball runtime. This operation is idempotent."
    )
    public RuntimeControlResult runtimeResume(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId
    ) {
        return runtimeBridge.resume(sessionId, runtimeId);
    }

    @Tool(
            name = "runtime_execute_step",
            description = "Execute one retry-friendly detached Pickleball step on the selected scenario thread. FAILED or UNAVAILABLE is returned as data so a controller can inspect the result and try another step without automatically failing the scenario."
    )
    public RuntimeControlResult runtimeExecuteStep(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId,
            @ToolParam(description = "Pickleball/Cucumber step text to create and execute.") String text,
            @ToolParam(description = "Optional doc-string/table argument text.", required = false)
            String argument,
            @ToolParam(description = "Seconds to wait for the scenario-thread command. Defaults to 60; maximum 3600.", required = false)
            Integer timeoutSeconds
    ) {
        return runtimeBridge.executeStep(
                sessionId,
                runtimeId,
                text,
                argument,
                timeoutSeconds
        );
    }


}
