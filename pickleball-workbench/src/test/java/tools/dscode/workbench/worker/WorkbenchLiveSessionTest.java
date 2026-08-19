package tools.dscode.workbench.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchLiveSessionTest {

    @TempDir
    Path tempDir;

    @Test
    void liveOperationsRequirePausedOwnedWorker() {
        try (WorkbenchLiveSession live = new WorkbenchLiveSession(tempDir)) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> live.executeStep("---not-running")
            );
            assertTrue(failure.getMessage().contains("paused interactive worker"));
        }
    }
}
