package tools.dscode.studio.build;

import tools.dscode.studio.process.ManagedProcessService;
import tools.dscode.studio.process.ManagedProcessSummary;
import tools.dscode.studio.process.ProcessResult;
import tools.dscode.studio.process.WorkspaceProcessService;
import tools.dscode.studio.workspace.WorkspaceInfo;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MavenBuildService {
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    private final WorkspaceInfo workspace;
    private final WorkspaceProcessService processes;
    private final ManagedProcessService managedProcesses;
    private final MavenToolchainService toolchain;

    public MavenBuildService(WorkspaceInfo workspace, WorkspaceProcessService processes) {
        this(workspace, processes, null, new MavenToolchainService());
    }

    public MavenBuildService(
            WorkspaceInfo workspace,
            WorkspaceProcessService processes,
            ManagedProcessService managedProcesses
    ) {
        this(workspace, processes, managedProcesses, new MavenToolchainService());
    }

    MavenBuildService(
            WorkspaceInfo workspace,
            WorkspaceProcessService processes,
            MavenToolchainService toolchain
    ) {
        this(workspace, processes, null, toolchain);
    }

    MavenBuildService(
            WorkspaceInfo workspace,
            WorkspaceProcessService processes,
            ManagedProcessService managedProcesses,
            MavenToolchainService toolchain
    ) {
        this.workspace = workspace;
        this.processes = processes;
        this.managedProcesses = managedProcesses;
        this.toolchain = toolchain;
    }

    public MavenRunResult run(List<String> arguments, Integer timeoutSeconds) {
        PreparedMaven prepared = prepare(arguments, timeoutSeconds);
        ProcessResult result = processes.run(
                prepared.command(),
                Path.of("."),
                prepared.timeoutSeconds(),
                prepared.environment()
        );
        return new MavenRunResult(MavenToolchainService.MAVEN_VERSION, result);
    }

    public ManagedMavenRunResult start(List<String> arguments, Integer timeoutSeconds) {
        return start(arguments, timeoutSeconds, Map.of());
    }

    public ManagedMavenRunResult start(
            List<String> arguments,
            Integer timeoutSeconds,
            Map<String, String> additionalEnvironment
    ) {
        if (managedProcesses == null) {
            throw new IllegalStateException("Managed Maven execution requires ManagedProcessService");
        }

        PreparedMaven prepared = prepare(arguments, timeoutSeconds);
        ManagedProcessSummary process = managedProcesses.start(
                prepared.command(),
                Path.of("."),
                prepared.timeoutSeconds(),
                environment(prepared.environment(), additionalEnvironment)
        );
        return new ManagedMavenRunResult(MavenToolchainService.MAVEN_VERSION, process);
    }

    private static Map<String, String> environment(
            Map<String, String> fixed,
            Map<String, String> additional
    ) {
        Map<String, String> result = new HashMap<>();
        if (additional != null) {
            result.putAll(additional);
        }
        result.putAll(fixed);
        return Map.copyOf(result);
    }

    private PreparedMaven prepare(List<String> arguments, Integer timeoutSeconds) {
        if (!workspace.mavenProject()) {
            throw new IllegalArgumentException("Workspace is not a Maven project: " + workspace.root());
        }
        if (arguments == null || arguments.isEmpty()) {
            throw new IllegalArgumentException("Maven arguments must not be empty");
        }

        int timeout = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        MavenRuntime runtime = toolchain.prepare();
        return new PreparedMaven(
                command(runtime, workspace.root(), arguments),
                timeout,
                Map.of("MAVEN_HOME", runtime.home().toString())
        );
    }

    static List<String> command(MavenRuntime runtime, Path workspaceRoot, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-Dmaven.home=" + runtime.home());
        command.add("-Dmaven.conf=" + runtime.home().resolve("conf"));
        command.add("-Dmaven.multiModuleProjectDirectory=" + workspaceRoot);
        command.add("-classpath");
        command.add(runtime.libDirectory() + File.separator + "*");
        command.add("org.apache.maven.cli.MavenCli");
        command.add("--batch-mode");
        command.add("--no-transfer-progress");
        command.addAll(arguments);
        return command;
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private record PreparedMaven(
            List<String> command,
            int timeoutSeconds,
            Map<String, String> environment
    ) {
    }
}
