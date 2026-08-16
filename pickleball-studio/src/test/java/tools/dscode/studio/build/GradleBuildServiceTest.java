package tools.dscode.studio.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.studio.process.ManagedProcessService;
import tools.dscode.studio.process.ManagedProcessSummary;
import tools.dscode.studio.process.ProcessState;
import tools.dscode.studio.process.WorkspaceProcessService;
import tools.dscode.studio.workspace.WorkspaceInfo;
import tools.dscode.studio.workspace.WorkspaceService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GradleBuildServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void runsProjectWrapperWithoutHostGradle() throws Exception {
        Path workspaceRoot = createGradleWorkspace("gradle wrapper space");
        WorkspaceInfo workspace = new WorkspaceService().open(workspaceRoot);
        GradleRunResult result = new GradleBuildService(
                workspace,
                new WorkspaceProcessService(workspace)
        ).run(List.of("--version"), 30);

        assertEquals(wrapperName(), result.wrapper());
        assertEquals(0, result.process().exitCode(), result.process().stderr());
        assertTrue(result.process().stdout().contains("studio-wrapper"), result.process().stdout());
        assertTrue(result.process().stdout().contains("--no-daemon"), result.process().stdout());
        assertTrue(result.process().stdout().contains("--console=plain"), result.process().stdout());
        assertTrue(result.process().stdout().contains("--version"), result.process().stdout());
        assertTrue(result.process().stdout().contains(System.getProperty("java.home")), result.process().stdout());
    }

    @Test
    void startsProjectWrapperAsManagedProcess() throws Exception {
        Path workspaceRoot = createGradleWorkspace("managed-gradle");
        WorkspaceInfo workspace = new WorkspaceService().open(workspaceRoot);
        WorkspaceProcessService processes = new WorkspaceProcessService(workspace);

        try (ManagedProcessService managed = new ManagedProcessService(processes)) {
            ManagedGradleRunResult started = new GradleBuildService(workspace, processes, managed)
                    .start(List.of("help"), 30);

            assertEquals(wrapperName(), started.wrapper());
            ManagedProcessSummary completed = waitForTerminal(managed, started.process().id());
            assertEquals(ProcessState.SUCCEEDED, completed.state());
            assertEquals(0, completed.exitCode());
        }
    }

    @Test
    void requiresProjectWrapper() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");
        WorkspaceInfo workspace = new WorkspaceService().open(tempDir);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new GradleBuildService(workspace, new WorkspaceProcessService(workspace))
                        .run(List.of("help"), 30)
        );

        assertTrue(error.getMessage().contains("Wrapper script"), error.getMessage());
    }

    private Path createGradleWorkspace(String name) throws Exception {
        Path root = tempDir.resolve(name);
        Files.createDirectories(root);
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n");

        if (isWindows()) {
            Files.writeString(
                    root.resolve("gradlew.bat"),
                    "@echo off\r\n"
                            + "echo studio-wrapper %*\r\n"
                            + "echo JAVA_HOME=%JAVA_HOME%\r\n"
                            + "exit /b 0\r\n"
            );
        } else {
            Files.writeString(root.resolve("gradlew"), """
                    #!/bin/sh
                    echo "studio-wrapper $*"
                    echo "JAVA_HOME=$JAVA_HOME"
                    """);
        }
        return root;
    }

    private static ManagedProcessSummary waitForTerminal(
            ManagedProcessService managed,
            String id
    ) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            ManagedProcessSummary summary = managed.status(id);
            if (summary.state() != ProcessState.RUNNING) {
                return summary;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Managed Gradle process did not finish");
    }

    private static String wrapperName() {
        return isWindows() ? "gradlew.bat" : "gradlew";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
