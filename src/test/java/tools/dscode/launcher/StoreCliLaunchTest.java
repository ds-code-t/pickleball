package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreCliLaunchTest {
    @TempDir
    Path project;

    @Test
    void launcherRoutesStoreBeforeWorkbenchDispatch() throws Exception {
        SpineSession.create(project, "agent-1");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = PickleballWorkbenchLauncher.dispatchStoreIfAny(
                new String[] {"store", "status", "--project=" + project},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Store database missing: .pickleball/store/main.db"), out);
        assertFalse(Files.exists(project.resolve(".pickleball").resolve("store").resolve("main.db")));
        assertEquals(-1, PickleballWorkbenchLauncher.dispatchStoreIfAny(
                new String[] {"help"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        ));
    }
}
