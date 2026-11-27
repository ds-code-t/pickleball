package tools.dscode.engine.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Heap gate through {@link ExecutionContext#open} and a real engine step.
 * The step body must run in the cold child, not in this JVM.
 */
class SubprocessStepTest {
    @TempDir
    Path project;

    @AfterEach
    void reset() {
        while (ExecutionContext.current() != null) ExecutionContext.current().close();
        ResourceGate.clearInjected();
        WarmSpine.shared().stopChildren();
        System.clearProperty("pkb_warm");
        System.clearProperty("pkb_store");
        System.clearProperty("pkb_store_run_classpath");
        System.clearProperty("pkb_engine_log");
    }

    @Test
    void subprocessStepRunsInTheColdChild() throws Exception {
        arm();
        Path pidFile = project.resolve("pid.txt");
        String path = pidFile.toAbsolutePath().toString().replace('\\', '/');
        RunResult result = PickleballEngine.run(
                java.util.List.of("the subprocess records its pid to \"" + path + "\""),
                EngineOptions.of(SubprocessProbe.class).project(project));
        assertEquals(Status.PASSED, result.status(), result.message());
        long written = Long.parseLong(Files.readString(pidFile).trim());
        assertNotEquals(ProcessHandle.current().pid(), written);
        assertEquals(WarmSpine.shared().lastTrackedPid(), written);
        assertEquals(Long.toString(written), result.scenarios().getFirst().steps().getFirst().returned());
    }

    @Test
    void subprocessFailureReachesTheParent() {
        arm();
        RunResult result = PickleballEngine.run(
                java.util.List.of("the subprocess fails with \"boom\""),
                EngineOptions.of(SubprocessProbe.class).project(project));
        assertEquals(Status.FAILED, result.status(), result.message());
        assertTrue(result.message().contains("boom"), result.message());
        assertNotEquals(ProcessHandle.current().pid(), WarmSpine.shared().lastTrackedPid());
        assertTrue(WarmSpine.shared().lastTrackedPid() > 0);
    }

    private void arm() {
        System.setProperty("pkb_warm", "on");
        System.setProperty("pkb_store", "false");
        System.setProperty("pkb_engine_log", "gate");
        System.setProperty("pkb_store_run_classpath", System.getProperty("java.class.path"));
        ResourceGate.inject(() -> new ResourceGate.Reading(75, 100, 1, 1, 1));
    }
}
