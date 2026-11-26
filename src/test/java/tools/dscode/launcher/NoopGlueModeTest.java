package tools.dscode.launcher;

import com.example.noop.EmptySteps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.engine.runtime.NoopGlueMode;
import tools.dscode.engine.adapter.cucumber.SharedStepRun;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Empty consumer glue is visible, and pkb_noop_glue=fail is a hard failure. */
class NoopGlueModeTest {
    @TempDir
    Path project;

    @Test
    void emptyGlueWarnsAndFailModeThrows() throws Exception {
        Method empty = EmptySteps.class.getDeclaredMethod("click", String.class);
        Method real = EmptySteps.class.getDeclaredMethod("real", String.class);
        String previous = System.getProperty(NoopGlueMode.KEY);
        System.clearProperty(NoopGlueMode.KEY);
        try {
            assertFalse(NoopGlueMode.fail());
            assertDoesNotThrow(() -> SharedStepRun.noteConsumerNoop(real));
            assertDoesNotThrow(() -> SharedStepRun.noteConsumerNoop(empty));
            System.setProperty(NoopGlueMode.KEY, "fail");
            assertThrows(IllegalStateException.class, () -> SharedStepRun.noteConsumerNoop(empty));
            assertDoesNotThrow(() -> SharedStepRun.noteConsumerNoop(real));
        } finally {
            restore(NoopGlueMode.KEY, previous);
        }
    }

    @Test
    void runvarOverrideFileFailsWithoutTheSystemProperty() throws Exception {
        String previousGlue = System.getProperty(NoopGlueMode.KEY);
        String previousProject = System.getProperty("pkb_project");
        System.clearProperty(NoopGlueMode.KEY);
        System.setProperty("pkb_project", project.toString());
        try {
            RunVarOverrides.publish(project, List.of("pkb_noop_glue=fail", "pkb_browser=CHROME_HEADLESS"));
            System.clearProperty(NoopGlueMode.KEY);
            assertTrue(Files.isRegularFile(project.resolve(NoopGlueMode.FILE)));
            assertTrue(NoopGlueMode.fail());
            Method empty = EmptySteps.class.getDeclaredMethod("click", String.class);
            assertThrows(IllegalStateException.class, () -> SharedStepRun.noteConsumerNoop(empty));
        } finally {
            restore(NoopGlueMode.KEY, previousGlue);
            restore("pkb_project", previousProject);
        }
    }

    @Test
    void compactRunvarsAndWorkspaceRunvarsFailTheStep() throws Exception {
        String previousCompact = System.getProperty("pkb_runvars");
        String previousGlue = System.getProperty(NoopGlueMode.KEY);
        String previousProject = System.getProperty("pkb_project");
        String previousRun = System.getProperty("pkb_run_id");
        System.clearProperty(NoopGlueMode.KEY);
        System.setProperty("pkb_runvars", "pkb_tags=@a, pkb_noop_glue=fail");
        try {
            assertTrue(NoopGlueMode.fail());
        } finally {
            restore("pkb_runvars", previousCompact);
            restore(NoopGlueMode.KEY, previousGlue);
        }
        System.clearProperty(NoopGlueMode.KEY);
        System.clearProperty("pkb_runvars");
        System.setProperty("pkb_project", project.toString());
        System.setProperty("pkb_run_id", "row-1");
        try {
            Workspace.writeRunVars(Workspace.directory(project, "row-1"), Map.of("pkb_noop_glue", "fail"));
            assertTrue(NoopGlueMode.fail());
        } finally {
            restore(NoopGlueMode.KEY, previousGlue);
            restore("pkb_project", previousProject);
            restore("pkb_run_id", previousRun);
            restore("pkb_runvars", previousCompact);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }
}
