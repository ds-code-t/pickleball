package tools.dscode.studio.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.studio.workspace.WorkspaceInfo;
import tools.dscode.studio.workspace.WorkspaceService;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceProcessServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void runsProcessAndCapturesOutput() {
        WorkspaceInfo workspace = new WorkspaceService().open(tempDir);
        WorkspaceProcessService processes = new WorkspaceProcessService(workspace);

        ProcessResult result = processes.run(
                List.of(javaExecutable().toString(), "-version"),
                ".",
                30
        );

        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.stderr().contains("version") || result.stdout().contains("version"));
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}
