package tools.dscode.workbench.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.WorkbenchController;
import tools.dscode.workbench.WorkbenchSessionActions;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchWindowLifecycleTest {
    @AfterEach
    void reset() {
        WorkbenchWindowLifecycle.resetForTests();
    }

    @Test
    void theLastDedicatedWindowCloseExitsAndASharedControllerDoesNot() {
        AtomicInteger exitCode = new AtomicInteger(-1);
        WorkbenchWindowLifecycle.exitForTests(exitCode::set);

        WorkbenchWindowLifecycle.armSharedController();
        WorkbenchWindowLifecycle.opened();
        WorkbenchWindowLifecycle.finish(() -> { });
        assertEquals(-1, exitCode.get(), "open-ui must not exit the session JVM");
        assertEquals(0, WorkbenchWindowLifecycle.openFramesForTests());

        WorkbenchWindowLifecycle.armDedicatedProcess();
        WorkbenchWindowLifecycle.opened();
        WorkbenchWindowLifecycle.opened();
        boolean[] disposed = new boolean[1];
        WorkbenchWindowLifecycle.finish(() -> disposed[0] = true);
        assertTrue(disposed[0]);
        assertEquals(-1, exitCode.get(), "one window still open");
        assertEquals(1, WorkbenchWindowLifecycle.openFramesForTests());
        WorkbenchWindowLifecycle.finish(() -> { });
        assertEquals(0, exitCode.get(), "last dedicated window exits");
        assertEquals(0, WorkbenchWindowLifecycle.openFramesForTests());
        assertTrue(WorkbenchWindowLifecycle.dedicatedProcess());
    }

    @Test
    void openUiClearsTheDedicatedExitSoTheSessionProcessStays(@TempDir Path project) {
        WorkbenchWindowLifecycle.armDedicatedProcess();
        assertTrue(WorkbenchWindowLifecycle.dedicatedProcess());
        try (WorkbenchController controller = new WorkbenchController(project)) {
            WorkbenchSessionActions actions = new WorkbenchSessionActions(controller);
            Map<String, Object> opened = actions.openUi("run-1", "agent-1");
            assertEquals("SUCCESS", opened.get("status"));
            assertTrue(java.util.Set.of("HEADLESS", "OPENING").contains(String.valueOf(opened.get("playback"))));
        }
        assertFalse(WorkbenchWindowLifecycle.dedicatedProcess());
    }
}
