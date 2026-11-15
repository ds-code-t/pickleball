package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltCliLaunchTest {
    @TempDir
    Path project;

    @Test
    void launcherRoutesDoltBeforeWorkbenchDispatch() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = PickleballWorkbenchLauncher.dispatchDoltIfAny(
                new String[] {"dolt", "status", "--project=" + project},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Dolt database missing: .pickleball/dolt/data"), out);
        assertFalse(Files.exists(project.resolve(".pickleball")));
        assertEquals(-1, PickleballWorkbenchLauncher.dispatchDoltIfAny(
                new String[] {"help"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        ));
    }
}
