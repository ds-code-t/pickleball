
package tools.dscode.studio.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.studio.build.GradleBuildService;
import tools.dscode.studio.build.MavenBuildService;
import tools.dscode.studio.process.ManagedProcessService;
import tools.dscode.studio.process.ManagedProcessSummary;
import tools.dscode.studio.process.ProcessOutputChunk;
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

class RuntimeBridgeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void runtimeStartAddsBridgeEnvironmentOnlyToManagedControlRun() throws Exception {
        Path workspaceRoot = createGradleWorkspace();
        WorkspaceInfo workspace = new WorkspaceService().open(workspaceRoot);
        WorkspaceProcessService processes = new WorkspaceProcessService(workspace);

        try (ManagedProcessService managed = new ManagedProcessService(processes);
             RuntimeBridgeService runtime = new RuntimeBridgeService(
                     workspace,
                     new MavenBuildService(workspace, processes, managed),
                     new GradleBuildService(workspace, processes, managed),
                     tempDir.resolve("sessions")
             )) {

            RuntimeLaunchResult started = runtime.start(List.of("help"), 30, true);
            ManagedProcessSummary completed = waitForTerminal(managed, started.process().id());
            ProcessOutputChunk output = managed.output(
                    started.process().id(),
                    0L,
                    0L,
                    null
            );

            assertEquals("Gradle", started.buildTool());
            assertEquals(ProcessState.SUCCEEDED, completed.state());
            assertTrue(output.stdout().contains("BRIDGE_SESSION=" + started.sessionId()), output.stdout());
            assertTrue(output.stdout().contains("BRIDGE_PAUSE=true"), output.stdout());
            assertTrue(output.stdout().contains("BRIDGE_TOKEN_SET"), output.stdout());
            assertTrue(
                    Files.isDirectory(tempDir.resolve("sessions").resolve(started.sessionId()))
            );
            assertTrue(runtime.list(started.sessionId()).isEmpty());

            var ordinary = new GradleBuildService(workspace, processes, managed)
                    .start(List.of("help"), 30);
            waitForTerminal(managed, ordinary.process().id());
            ProcessOutputChunk ordinaryOutput = managed.output(
                    ordinary.process().id(),
                    0L,
                    0L,
                    null
            );
            assertTrue(
                    ordinaryOutput.stdout().contains("BRIDGE_SESSION=")
                            && !ordinaryOutput.stdout().contains(started.sessionId()),
                    ordinaryOutput.stdout()
            );
        }
    }

    private Path createGradleWorkspace() throws Exception {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n");

        if (isWindows()) {
            Files.writeString(
                    root.resolve("gradlew.bat"),
                    "@echo off\r\n"
                            + "echo BRIDGE_SESSION=%PKB_STUDIO_BRIDGE_SESSION_ID%\r\n"
                            + "echo BRIDGE_PAUSE=%PKB_STUDIO_BRIDGE_PAUSE_FIRST_SCENARIO%\r\n"
                            + "if defined PKB_STUDIO_BRIDGE_TOKEN echo BRIDGE_TOKEN_SET\r\n"
                            + "exit /b 0\r\n"
            );
        } else {
            Files.writeString(root.resolve("gradlew"), """
                    #!/bin/sh
                    echo "BRIDGE_SESSION=$PKB_STUDIO_BRIDGE_SESSION_ID"
                    echo "BRIDGE_PAUSE=$PKB_STUDIO_BRIDGE_PAUSE_FIRST_SCENARIO"
                    if [ -n "$PKB_STUDIO_BRIDGE_TOKEN" ]; then
                      echo "BRIDGE_TOKEN_SET"
                    fi
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
        throw new AssertionError("Managed runtime build did not finish");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
