package tools.dscode.studio.build;

import tools.dscode.studio.process.ProcessResult;
import tools.dscode.studio.process.WorkspaceProcessService;
import tools.dscode.studio.workspace.WorkspaceInfo;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MavenBuildService {
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    private final WorkspaceInfo workspace;
    private final WorkspaceProcessService processes;
    private final MavenToolchainService toolchain;

    public MavenBuildService(WorkspaceInfo workspace, WorkspaceProcessService processes) {
        this(workspace, processes, new MavenToolchainService());
    }

    MavenBuildService(
            WorkspaceInfo workspace,
            WorkspaceProcessService processes,
            MavenToolchainService toolchain
    ) {
        this.workspace = workspace;
        this.processes = processes;
        this.toolchain = toolchain;
    }

    public MavenRunResult run(List<String> arguments, Integer timeoutSeconds) {
        if (!workspace.mavenProject()) {
            throw new IllegalArgumentException("Workspace is not a Maven project: " + workspace.root());
        }
        if (arguments == null || arguments.isEmpty()) {
            throw new IllegalArgumentException("Maven arguments must not be empty");
        }

        int timeout = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        MavenRuntime runtime = toolchain.prepare();
        List<String> command = command(runtime, workspace.root(), arguments);
        ProcessResult result = processes.run(
                command,
                Path.of("."),
                timeout,
                Map.of("MAVEN_HOME", runtime.home().toString())
        );
        return new MavenRunResult(MavenToolchainService.MAVEN_VERSION, result);
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
}
