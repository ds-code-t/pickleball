package tools.dscode.engine.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.model.StepResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One parent scenario that the heap gate runs in a cold child. The child keeps
 * one scenario across every step. The parent report matches a cold in-process run.
 */
class SubprocessScenarioTest {
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
    void oneChildScenarioCarriesStateAcrossSteps() throws Exception {
        System.setProperty("pkb_warm", "off");
        System.setProperty("pkb_store", "false");
        RunResult cold = run(project.resolve("cold.txt"));
        assertCarried(cold, project.resolve("cold.txt"));

        arm();
        Path out = project.resolve("child.txt");
        RunResult child = run(out);
        assertEquals(Status.FAILED, child.status(), child.message());
        assertTrue(child.message().contains("boom"), child.message());
        List<StepResult> steps = child.scenarios().getFirst().steps();
        assertEquals(statuses(cold), statuses(child));
        assertEquals(3, steps.size());
        String returned = steps.get(1).returned();
        assertTrue(returned.startsWith("carried|carried|"), returned);
        long pid = Long.parseLong(returned.substring(returned.lastIndexOf('|') + 1));
        assertNotEquals(ProcessHandle.current().pid(), pid);
        assertEquals(WarmSpine.shared().lastTrackedPid(), pid);
        assertEquals(Long.toString(pid), steps.get(0).returned());
        assertTrue(steps.get(2).status() == Status.FAILED, steps.get(2).status().name());
        assertTrue(child.message().contains("pid=" + pid), child.message());
        assertEquals(returned, Files.readString(out).trim());
        assertTrue(cold.scenarios().getFirst().steps().get(1).returned().startsWith("carried|carried|"),
                cold.scenarios().getFirst().steps().get(1).returned());
    }

    private static void assertCarried(RunResult result, Path out) throws Exception {
        assertEquals(Status.FAILED, result.status(), result.message());
        assertTrue(result.message().contains("boom"), result.message());
        List<StepResult> steps = result.scenarios().getFirst().steps();
        assertEquals(List.of(Status.PASSED, Status.PASSED, Status.FAILED), statusesOf(steps));
        assertTrue(steps.get(1).returned().startsWith("carried|carried|"), steps.get(1).returned());
        assertEquals(steps.get(1).returned(), Files.readString(out).trim());
    }

    private RunResult run(Path out) {
        String path = out.toAbsolutePath().toString().replace('\\', '/');
        return PickleballEngine.run(List.of(
                "the subprocess scenario stores \"carried\"",
                "the subprocess scenario reads into \"" + path + "\"",
                "the subprocess scenario fails with \"boom\""
        ), EngineOptions.of(SubprocessScenarioProbe.class).project(project));
    }

    private void arm() {
        System.setProperty("pkb_warm", "on");
        System.setProperty("pkb_store", "false");
        System.setProperty("pkb_engine_log", "gate");
        System.setProperty("pkb_store_run_classpath", System.getProperty("java.class.path"));
        ResourceGate.inject(() -> new ResourceGate.Reading(75, 100, 1, 1, 1));
    }

    private static List<Status> statuses(RunResult result) {
        return statusesOf(result.scenarios().getFirst().steps());
    }

    private static List<Status> statusesOf(List<StepResult> steps) {
        return steps.stream().map(StepResult::status).toList();
    }
}
