package tools.dscode.workbench.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.ControlBridgeDescriptor;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.sync.WorkbenchSyncMode;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                "-D" + ControlProtocol.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY + "=" + live
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
        assertTrue(command.contains(ControlProtocol.WORKER_MAIN_CLASS));
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
                "-D" + ControlProtocol.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY + "=" + live
        );
        assertEquals("-Dpkb_browser=CHROME_HEADLESS", command.get(outputRoot + 1));
        assertEquals("-Dpkb_tags=@smoke", command.get(outputRoot + 2));
        assertTrue(command.indexOf("-Dpkb_tags=@smoke") < command.indexOf("-cp"));
    }

    @Test
    void isolateReplayUsesCompactPkbRunvarsNotRunProfileInput() {
        Path project = tempDir.resolve("consumer").toAbsolutePath().normalize();
        Path live = project.resolve(".pickleball/workbench/live/classes");
        Path dependency = tempDir.resolve("pickleball.jar").toAbsolutePath().normalize();
        Path anchor = project.resolve(".pickleball/workbench/sessions/test/anchor.feature");
        String runVars = "pkb_browser=CHROME_HEADLESS, pkb_glue=com.example, pkb_parallel=1";

        List<String> command = WorkbenchWorkerManager.workerCommand(
                manifest(project, live, dependency),
                List.of(live.toString(), dependency.toString()),
                anchor,
                Map.of("pkb_runvars", runVars)
        );

        assertTrue(command.contains("-Dpkb_runvars=" + runVars));
        assertFalse(command.stream().anyMatch(item -> item.startsWith("-Dpkb_run_profile=")));
        assertFalse(command.stream().anyMatch(item -> item.equals("-Dpkb_browser=chrome")));
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

    @Test
    void consumerRuntimeMustBeASeparateProcessLoadedFromCapturedClasspath() {
        Path project = tempDir.resolve("consumer").toAbsolutePath().normalize();
        Path live = project.resolve(".pickleball/workbench/live/classes");
        Path dependency = tempDir.resolve("pickleball.jar").toAbsolutePath().normalize();
        WorkbenchManifest manifest = manifest(project, live, dependency);
        ControlBridgeDescriptor descriptor = descriptor(
                ProcessHandle.current().pid() + 1,
                dependency,
                "2.1.8"
        );

        WorkbenchWorkerManager.verifyConsumerRuntime(
                descriptor,
                manifest,
                List.of(live.toString(), dependency.toString())
        );
    }

    @Test
    void consumerRuntimeRejectsControllerPidUncapturedOriginAndVersionDrift() {
        Path project = tempDir.resolve("consumer").toAbsolutePath().normalize();
        Path live = project.resolve(".pickleball/workbench/live/classes");
        Path dependency = tempDir.resolve("pickleball.jar").toAbsolutePath().normalize();
        Path foreign = tempDir.resolve("controller/pickleball.jar").toAbsolutePath().normalize();
        Path controllerJar = tempDir.resolve("pickleball-workbench-2.1.8.jar")
                .toAbsolutePath()
                .normalize();
        WorkbenchManifest manifest = manifest(project, live, dependency);
        List<String> classpath = List.of(live.toString(), dependency.toString());

        IllegalStateException pidFailure = assertThrows(
                IllegalStateException.class,
                () -> WorkbenchWorkerManager.verifyConsumerRuntime(
                        descriptor(ProcessHandle.current().pid(), dependency, "2.1.8"),
                        manifest,
                        classpath
                )
        );
        IllegalStateException originFailure = assertThrows(
                IllegalStateException.class,
                () -> WorkbenchWorkerManager.verifyConsumerRuntime(
                        descriptor(ProcessHandle.current().pid() + 1, foreign, "2.1.8"),
                        manifest,
                        classpath
                )
        );
        IllegalStateException versionFailure = assertThrows(
                IllegalStateException.class,
                () -> WorkbenchWorkerManager.verifyConsumerRuntime(
                        descriptor(ProcessHandle.current().pid() + 1, dependency, "9.9.9"),
                        manifest,
                        classpath
                )
        );
        IllegalStateException controllerLeakFailure = assertThrows(
                IllegalStateException.class,
                () -> WorkbenchWorkerManager.verifyConsumerRuntime(
                        descriptor(ProcessHandle.current().pid() + 1, dependency, "2.1.8"),
                        manifest,
                        List.of(live.toString(), dependency.toString(), controllerJar.toString())
                )
        );
        IllegalStateException duplicateOriginFailure = assertThrows(
                IllegalStateException.class,
                () -> WorkbenchWorkerManager.verifyConsumerRuntime(
                        descriptor(ProcessHandle.current().pid() + 1, dependency, "2.1.8"),
                        manifest,
                        List.of(live.toString(), dependency.toString(), dependency.toString())
                )
        );
        IllegalStateException missingVersionFailure = assertThrows(
                IllegalStateException.class,
                () -> WorkbenchWorkerManager.verifyConsumerRuntime(
                        descriptor(ProcessHandle.current().pid() + 1, dependency, null),
                        manifest,
                        classpath
                )
        );

        assertTrue(pidFailure.getMessage().contains("distinct"));
        assertTrue(originFailure.getMessage().contains("outside the synchronized"));
        assertTrue(versionFailure.getMessage().contains("does not match"));
        assertTrue(controllerLeakFailure.getMessage().contains("must not contain"));
        assertTrue(duplicateOriginFailure.getMessage().contains("exactly once"));
        assertTrue(missingVersionFailure.getMessage().contains("did not report"));
    }

    private ControlBridgeDescriptor descriptor(long pid, Path runtimeSource, String version) {
        return new ControlBridgeDescriptor(
                ControlProtocol.CURRENT_VERSION,
                ControlProtocol.MINIMUM_COMPATIBLE_VERSION,
                "session",
                "runtime",
                pid,
                "127.0.0.1",
                1,
                "2026-08-20T00:00:00Z",
                version,
                runtimeSource.toString(),
                ControlProtocol.WORKER_CAPABILITIES
        );
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
                System.getProperty("java.home"),
                WorkbenchSyncMode.FULL.name(),
                "java-fp",
                "resource-fp",
                "build-fp",
                "dep-fp"
        );
    }

    private static final class WorkbenchProjectOs {
        private static String javaName() {
            return System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        }
    }
}
