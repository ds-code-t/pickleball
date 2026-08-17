package tools.dscode.studio.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.studio.workspace.WorkspaceInfo;
import tools.dscode.studio.workspace.WorkspaceService;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedProcessServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void capturesIncrementalOutputAndHistory() throws Exception {
        WorkspaceInfo workspace = new WorkspaceService().open(tempDir);
        try (ManagedProcessService managed = new ManagedProcessService(new WorkspaceProcessService(workspace))) {
            ManagedProcessSummary started = managed.start(javaCommand("delay"), ".", 5);

            ProcessOutputChunk first = waitForOutput(managed, started.id(), "first");
            assertTrue(first.stdout().contains("first"), first.stdout());

            ProcessOutputChunk finalOutput = waitForTerminalOutput(
                    managed,
                    started.id(),
                    first.nextStdoutOffset(),
                    first.nextStderrOffset()
            );

            assertEquals(ProcessState.SUCCEEDED, finalOutput.state());
            assertTrue(finalOutput.stderr().contains("second"), finalOutput.stderr());
            assertEquals(started.id(), managed.list(10).getFirst().id());
        }
    }

    @Test
    void cancelsRunningProcess() throws Exception {
        WorkspaceInfo workspace = new WorkspaceService().open(tempDir);
        try (ManagedProcessService managed = new ManagedProcessService(new WorkspaceProcessService(workspace))) {
            ManagedProcessSummary started = managed.start(javaCommand("sleep"), ".", 30);
            ManagedProcessSummary cancelled = managed.cancel(started.id());

            assertEquals(ProcessState.CANCELLED, cancelled.state());
            assertNotNull(cancelled.completedAt());
            assertEquals(ProcessState.CANCELLED, waitForTerminal(managed, started.id()).state());
        }
    }

    @Test
    void cancellationTerminatesDescendantProcesses() throws Exception {
        WorkspaceInfo workspace = new WorkspaceService().open(tempDir);
        try (ManagedProcessService managed = new ManagedProcessService(new WorkspaceProcessService(workspace))) {
            ManagedProcessSummary started = managed.start(javaCommand("spawn"), ".", 30);
            ProcessOutputChunk output = waitForOutput(managed, started.id(), "child=");
            long childPid = Long.parseLong(output.stdout().trim().substring("child=".length()));

            ManagedProcessSummary cancelled = managed.cancel(started.id());
            assertEquals(ProcessState.CANCELLED, cancelled.state());
            assertNotNull(cancelled.completedAt());
            assertEquals(ProcessState.CANCELLED, waitForTerminal(managed, started.id()).state());
            waitForProcessExit(childPid);
        }
    }

    private static ProcessOutputChunk waitForOutput(
            ManagedProcessService managed,
            String id,
            String expected
    ) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            ProcessOutputChunk chunk = managed.output(id, 0L, 0L, 4096);
            if (chunk.stdout().contains(expected)) {
                return chunk;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Expected process output was not observed");
    }

    private static ProcessOutputChunk waitForTerminalOutput(
            ManagedProcessService managed,
            String id,
            long stdoutOffset,
            long stderrOffset
    ) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            ProcessOutputChunk chunk = managed.output(id, stdoutOffset, stderrOffset, 4096);
            if (chunk.state() != ProcessState.RUNNING) {
                return chunk;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Managed process did not finish");
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
        throw new AssertionError("Managed process did not finish");
    }

    private static void waitForProcessExit(long pid) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (ProcessHandle.of(pid).isEmpty() || !ProcessHandle.of(pid).orElseThrow().isAlive()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Descendant process remained alive after cancellation: " + pid);
    }

    private static List<String> javaCommand(String mode) {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ManagedProcessFixture.class.getName(),
                mode
        );
    }
}
