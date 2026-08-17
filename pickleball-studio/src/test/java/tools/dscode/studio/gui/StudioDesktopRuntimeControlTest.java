package tools.dscode.studio.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.studio.process.ManagedProcessSummary;
import tools.dscode.studio.process.ProcessOutputChunk;
import tools.dscode.studio.process.ProcessState;
import tools.dscode.studio.runtime.RuntimeLaunchResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioDesktopRuntimeControlTest {

    @TempDir
    Path tempDir;

    @Test
    void desktopFacadeStartsOptInRuntimeControlRun() throws Exception {
        createGradleWorkspace();

        try (StudioDesktopSession session = StudioDesktopSession.open(tempDir)) {
            RuntimeLaunchResult launch = session.startControlledTests();
            ManagedProcessSummary completed = waitForTerminal(session, launch.process().id());
            ProcessOutputChunk output = session.processOutput(
                    launch.process().id(),
                    0L,
                    0L
            );

            assertEquals("Gradle", launch.buildTool());
            assertEquals(ProcessState.SUCCEEDED, completed.state());
            assertTrue(
                    output.stdout().contains("BRIDGE_SESSION=" + launch.sessionId()),
                    output.stdout()
            );
            assertTrue(output.stdout().contains("BRIDGE_PAUSE=true"), output.stdout());
            assertTrue(session.runtimeState(launch.sessionId(), null).runtimes().isEmpty());
        }
    }

    private void createGradleWorkspace() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");

        if (isWindows()) {
            Files.writeString(
                    tempDir.resolve("gradlew.bat"),
                    "@echo off\r\n"
                            + "echo BRIDGE_SESSION=%PKB_STUDIO_BRIDGE_SESSION_ID%\r\n"
                            + "echo BRIDGE_PAUSE=%PKB_STUDIO_BRIDGE_PAUSE_FIRST_SCENARIO%\r\n"
                            + "exit /b 0\r\n"
            );
        } else {
            Path wrapper = tempDir.resolve("gradlew");
            Files.writeString(wrapper, """
                    #!/bin/sh
                    echo "BRIDGE_SESSION=$PKB_STUDIO_BRIDGE_SESSION_ID"
                    echo "BRIDGE_PAUSE=$PKB_STUDIO_BRIDGE_PAUSE_FIRST_SCENARIO"
                    """);
            wrapper.toFile().setExecutable(true);
        }
    }

    private static ManagedProcessSummary waitForTerminal(
            StudioDesktopSession session,
            String id
    ) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            ManagedProcessSummary summary = session.processStatus(id);
            if (summary.state() != ProcessState.RUNNING) {
                return summary;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Desktop runtime control process did not finish");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
