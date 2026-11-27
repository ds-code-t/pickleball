package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineCliLaunchTest {
    @TempDir
    Path project;

    @Test
    void launcherRoutesEngineBeforeStoreAndWorkbench() throws Exception {
        SpineSession.create(project, "agent-1");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = PickleballWorkbenchLauncher.dispatchEngineIfAny(
                new String[]{"engine", "help", "--project=" + project},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("Usage: engine run"));
        assertEquals(-1, PickleballWorkbenchLauncher.dispatchEngineIfAny(
                new String[]{"store", "help"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        ));
    }
}
