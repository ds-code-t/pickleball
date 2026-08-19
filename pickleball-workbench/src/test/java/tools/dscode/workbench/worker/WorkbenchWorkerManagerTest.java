package tools.dscode.workbench.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.testengine.DynamicSuiteBootstrap;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchWorkerManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void directWorkerCommandUsesOnlyLiveOutputAndCapturedDependencies() {
        Path project = tempDir.resolve("consumer").toAbsolutePath().normalize();
        Path live = project.resolve(".pickleball/workbench/live/classes");
        Path dependency = tempDir.resolve("pickleball.jar").toAbsolutePath().normalize();
        Path anchor = project.resolve(".pickleball/workbench/sessions/test/anchor.feature");

        WorkbenchManifest manifest = manifest(project, live, dependency);
        List<String> command = WorkbenchWorkerManager.workerCommand(
                manifest,
                List.of(live.toString(), dependency.toString()),
                anchor
        );

        assertTrue(command.get(0).endsWith(WorkbenchProjectOs.javaName()));
        assertTrue(command.contains(
                "-D" + DynamicSuiteBootstrap.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY + "=" + live
        ));
        int classpathIndex = command.indexOf("-cp") + 1;
        assertEquals(
                live + File.pathSeparator + dependency,
                command.get(classpathIndex)
        );
        assertFalse(List.of(command.get(classpathIndex).split(java.util.regex.Pattern.quote(File.pathSeparator))).stream()
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .anyMatch(path -> path.startsWith(project.resolve(".pickleball/workbench/base"))));
        assertTrue(command.contains("tools.dscode.testengine.WorkbenchWorkerMain"));
        int tagIndex = command.indexOf("--tags");
        assertEquals("@pickleball-workbench-anchor", command.get(tagIndex + 1));
        assertEquals(anchor.toUri().toString(), command.getLast());
    }

    @Test
    void workerSystemPropertiesAreExplicitAndDeterministic() {
        Path project = tempDir.resolve("consumer").toAbsolutePath().normalize();
        Path live = project.resolve(".pickleball/workbench/live/classes");
        Path dependency = tempDir.resolve("pickleball.jar").toAbsolutePath().normalize();
        Path anchor = project.resolve(".pickleball/workbench/sessions/test/anchor.feature");

        List<String> command = WorkbenchWorkerManager.workerCommand(
                manifest(project, live, dependency),
                List.of(live.toString(), dependency.toString()),
                anchor,
                Map.of("pkb_tags", "@smoke", "pkb_browser", "CHROME_HEADLESS")
        );

        int outputRoot = command.indexOf(
                "-D" + DynamicSuiteBootstrap.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY + "=" + live
        );
        assertEquals("-Dpkb_browser=CHROME_HEADLESS", command.get(outputRoot + 1));
        assertEquals("-Dpkb_tags=@smoke", command.get(outputRoot + 2));
        assertTrue(command.indexOf("-Dpkb_tags=@smoke") < command.indexOf("-cp"));
    }

    @Test
    void interactiveWorkerUsesSafeAnchorStepPauseBoundary() {
        assertEquals("BEFORE_STEP", WorkbenchWorkerManager.INTERACTIVE_PAUSE_HOOK);
        assertEquals("---pickleball-workbench-anchor", WorkbenchWorkerManager.INTERACTIVE_PAUSE_STEP);
    }

    @Test
    void anchorUsesGuaranteedNoOpCoreStep() {
        String feature = WorkbenchWorkerManager.anchorFeature();

        assertTrue(feature.contains("@pickleball-workbench-anchor"));
        assertTrue(feature.contains("Given ---pickleball-workbench-anchor"));
        assertFalse(feature.contains("save "));
        assertFalse(feature.contains("@all"));
    }

    private WorkbenchManifest manifest(Path project, Path live, Path dependency) {
        return new WorkbenchManifest(
                1,
                project.toString(),
                "MAVEN",
                "mvnw.cmd",
                List.of(),
                List.of(),
                List.of(),
                live.toString(),
                "2026-08-19T00:00:00Z",
                "fingerprint",
                List.of(dependency.toString()),
                "2.1.8",
                "21",
                System.getProperty("java.home")
        );
    }

    private static final class WorkbenchProjectOs {
        private static String javaName() {
            return System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        }
    }
}
