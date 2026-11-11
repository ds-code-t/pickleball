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

class DaltonCliLaunchTest {
    @TempDir
    Path project;

    @Test
    void launcherRoutesDaltonBeforeWorkbenchDispatch() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = PickleballWorkbenchLauncher.dispatchDaltonIfAny(
                new String[] {"dalton", "status", "--project=" + project},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Dalton database missing: .pickleball/dalton/dalton.db"), out);
        assertFalse(Files.exists(project.resolve(".pickleball")));
        assertEquals(-1, PickleballWorkbenchLauncher.dispatchDaltonIfAny(
                new String[] {"help"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        ));
    }
}
