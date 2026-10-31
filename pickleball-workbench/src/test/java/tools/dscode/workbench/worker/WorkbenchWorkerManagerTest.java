package tools.dscode.workbench.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.ControlBridgeDescriptor;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.sync.WorkbenchSyncMode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
        assertTrue(command.contains("-Dfile.encoding=UTF-8"));
        assertTrue(command.contains("-Dstdout.encoding=UTF-8"));
        assertTrue(command.contains("-Dstderr.encoding=UTF-8"));
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

        Path workspace = Workspace.directory(project, "run-1");
        List<String> command = WorkbenchWorkerManager.workerCommand(
                manifest(project, live, dependency),
                List.of(live.toString(), dependency.toString()),
                anchor,
                Map.of("pkb_tags", "@smoke", "pkb_workspace", workspace.toString())
        );

        int outputRoot = command.indexOf(
                "-D" + ControlProtocol.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY + "=" + live
        );
        assertEquals("-Dpkb_tags=@smoke", command.get(outputRoot + 1));
        assertEquals("-Dpkb_workspace=" + workspace, command.get(outputRoot + 2));
        assertTrue(command.indexOf("-Dpkb_tags=@smoke") < command.indexOf("-cp"));
        assertFalse(command.stream().anyMatch(item -> item.startsWith("-Dpkb_runvars=")));
    }

    @Test
    void isolateLaunchUsesWorkspaceNotCompactRunVars() {
        Path project = tempDir.resolve("consumer").toAbsolutePath().normalize();
        Path live = project.resolve(".pickleball/workbench/live/classes");
        Path dependency = tempDir.resolve("pickleball.jar").toAbsolutePath().normalize();
        Path anchor = project.resolve(".pickleball/workbench/sessions/test/anchor.feature");
        Path workspace = Workspace.directory(project, "run-1");

        List<String> command = WorkbenchWorkerManager.workerCommand(
                manifest(project, live, dependency),
                List.of(live.toString(), dependency.toString()),
                anchor,
                Map.of("pkb_workspace", workspace.toString(), "pkb_tags", "@broken")
        );

        assertTrue(command.contains("-Dpkb_workspace=" + workspace));
        assertTrue(command.contains("-Dpkb_tags=@broken"));
        assertFalse(command.stream().anyMatch(item -> item.startsWith("-Dpkb_runvars=")));
        assertFalse(command.stream().anyMatch(item -> item.startsWith("-Dpkb_run_profile=")));
        assertFalse(command.stream().anyMatch(item -> item.startsWith("-Dpkb_overriderunvars=")));
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

    @Test
    void workerStartTimeoutDefaultsToThreeMinutesAndHonorsOverrides() {
        assertEquals(Duration.ofSeconds(180), WorkbenchWorkerManager.START_TIMEOUT);
        assertEquals(Duration.ofSeconds(180), WorkbenchWorkerManager.startTimeout(Map.of()));
        String property = WorkbenchWorkerManager.START_TIMEOUT_PROPERTY;
        String previous = System.getProperty(property);
        String previousRunVars = System.getProperty("pkb_runvars");
        String previousDotted = System.getProperty(WorkbenchWorkerManager.START_TIMEOUT_RUNVAR);
        try {
            System.setProperty(property, "12");
            assertEquals(Duration.ofSeconds(12), WorkbenchWorkerManager.startTimeout(Map.of()));
            System.clearProperty(property);
            System.setProperty("pkb_runvars", "pkb_browser=CHROME_HEADLESS, pkb_workbench_worker_start_timeout=45");
            assertEquals(Duration.ofSeconds(45), WorkbenchWorkerManager.startTimeout(Map.of()));
            System.clearProperty("pkb_runvars");
            System.setProperty(WorkbenchWorkerManager.START_TIMEOUT_RUNVAR, "30");
            assertEquals(Duration.ofSeconds(30), WorkbenchWorkerManager.startTimeout(Map.of()));
            System.clearProperty(WorkbenchWorkerManager.START_TIMEOUT_RUNVAR);
            assertEquals(
                    Duration.ofSeconds(90),
                    WorkbenchWorkerManager.startTimeout(Map.of(
                            "pkb_runvars", "pkb_workbench_worker_start_timeout=90"
                    ))
            );
            assertEquals(Duration.ofSeconds(180), WorkbenchWorkerManager.startTimeout(Map.of(property, "nope")));
        } finally {
            restore(property, previous);
            restore("pkb_runvars", previousRunVars);
            restore(WorkbenchWorkerManager.START_TIMEOUT_RUNVAR, previousDotted);
        }
    }

    @Test
    void progressLinesNameTheStageAndTimeoutIncludesTheTail() throws Exception {
        Path log = tempDir.resolve("worker.err.log");
        for (String stage : WorkbenchWorkerManager.STARTUP_STAGES) {
            WorkbenchWorkerManager.writeProgress(log, stage);
        }
        String text = Files.readString(log);
        assertTrue(text.contains("progress stage=compile"));
        assertTrue(text.contains("progress stage=classpath"));
        assertTrue(text.contains("progress stage=worker-launch"));
        assertTrue(text.contains("progress stage=anchor-parse"));
        assertTrue(text.contains("progress stage=paused"));
        String detail = WorkbenchWorkerManager.startupContext(Duration.ofMillis(1500), "anchor-parse", log);
        assertTrue(detail.contains("elapsed=1500ms"));
        assertTrue(detail.contains("stage=anchor-parse"));
        assertTrue(detail.contains("last-worker-output="));
        assertTrue(detail.contains("progress stage=paused"));
    }

    @Test
    void sessionStartWritesCompileProgressBeforeTheWorkerExists() throws IOException {
        Path project = tempDir.resolve("consumer");
        Files.createDirectories(project);
        WorkbenchWorkerManager manager = new WorkbenchWorkerManager(project);
        assertThrows(IllegalStateException.class, manager::startInteractive);
        Path logs = WorkbenchManifest.workbenchRoot(project).resolve("logs");
        assertTrue(Files.isDirectory(logs));
        boolean found = false;
        try (var listed = Files.list(logs)) {
            for (Path log : listed.toList()) {
                if (Files.readString(log).contains("progress stage=compile")) found = true;
            }
        }
        assertTrue(found);
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
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
