package tools.dscode.studio.build;

import tools.dscode.studio.process.ManagedProcessService;
import tools.dscode.studio.process.ManagedProcessSummary;
import tools.dscode.studio.process.ProcessResult;
import tools.dscode.studio.process.WorkspaceProcessService;
import tools.dscode.studio.workspace.WorkspaceInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GradleBuildService {
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    private final WorkspaceInfo workspace;
    private final WorkspaceProcessService processes;
    private final ManagedProcessService managedProcesses;

    public GradleBuildService(WorkspaceInfo workspace, WorkspaceProcessService processes) {
        this(workspace, processes, null);
    }

    public GradleBuildService(
            WorkspaceInfo workspace,
            WorkspaceProcessService processes,
            ManagedProcessService managedProcesses
    ) {
        this.workspace = workspace;
        this.processes = processes;
        this.managedProcesses = managedProcesses;
    }

    public GradleRunResult run(List<String> arguments, Integer timeoutSeconds) {
        PreparedGradle prepared = prepare(arguments, timeoutSeconds);
        ProcessResult result = processes.run(
                prepared.command(),
                Path.of("."),
                prepared.timeoutSeconds(),
                prepared.environment()
        );
        return new GradleRunResult(prepared.wrapper(), result);
    }

    public ManagedGradleRunResult start(List<String> arguments, Integer timeoutSeconds) {
        if (managedProcesses == null) {
            throw new IllegalStateException("Managed Gradle execution requires ManagedProcessService");
        }

        PreparedGradle prepared = prepare(arguments, timeoutSeconds);
        ManagedProcessSummary process = managedProcesses.start(
                prepared.command(),
                Path.of("."),
                prepared.timeoutSeconds(),
                prepared.environment()
        );
        return new ManagedGradleRunResult(prepared.wrapper(), process);
    }

    private PreparedGradle prepare(List<String> arguments, Integer timeoutSeconds) {
        if (!workspace.gradleProject()) {
            throw new IllegalArgumentException("Workspace is not a Gradle project: " + workspace.root());
        }
        if (arguments == null || arguments.isEmpty()) {
            throw new IllegalArgumentException("Gradle arguments must not be empty");
        }

        String wrapper = isWindows() ? "gradlew.bat" : "gradlew";
        if (!Files.isRegularFile(workspace.root().resolve(wrapper))) {
            throw new IllegalArgumentException(
                    "Gradle project does not contain the required Wrapper script: " + wrapper
            );
        }

        int timeout = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        return new PreparedGradle(
                wrapper,
                command(wrapper, arguments),
                timeout,
                Map.of("JAVA_HOME", System.getProperty("java.home"))
        );
    }

    static List<String> command(String wrapper, List<String> arguments) {
        List<String> command = new ArrayList<>();
        if (isWindows()) {
            command.add(System.getenv().getOrDefault("ComSpec", "cmd.exe"));
            command.add("/d");
            command.add("/c");
            command.add(wrapper);
        } else {
            command.add("sh");
            command.add("./" + wrapper);
        }
        command.add("--no-daemon");
        command.add("--console=plain");
        command.addAll(arguments);
        return command;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    private record PreparedGradle(
            String wrapper,
            List<String> command,
            int timeoutSeconds,
            Map<String, String> environment
    ) {
    }
}
