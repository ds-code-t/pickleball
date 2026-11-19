package tools.dscode.control.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.api.NativeDynamicSteps;
import tools.dscode.control.protocol.ControlBridgeCallResult;
import tools.dscode.control.protocol.NoopNotice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B16: an empty consumer step is flagged on the result and written into the run report. */
class NoopGlueReportTest {
    @TempDir
    Path project;

    @Test
    void emptyConsumerStepWarnsThenFailsWhenAsked() throws Exception {
        NativeDynamicSteps.useGlue(com.example.glue.NoopSamples.class);
        System.setProperty("pkb_project", project.toString());
        System.setProperty("pkb_run_id", "noop-run");
        System.clearProperty("pkb_noop_glue");
        try (ControlBridgeCoordinator coordinator = new ControlBridgeCoordinator("native", 1L, List.of(), false, null)) {
            ControlBridgeCallResult warned = coordinator.executeStep(null, "agent fast empty glue", "", 5);
            assertEquals("SUCCESS", warned.status(), String.valueOf(warned));
            assertTrue(String.valueOf(warned.valueText()).contains(NoopNotice.MARK), String.valueOf(warned));
            String report = Files.readString(project.resolve(".pickleball/runs/noop-run/run-report.txt"));
            assertTrue(report.contains(NoopNotice.WARNING), report);

            System.setProperty("pkb_noop_glue", "fail");
            ControlBridgeCallResult failed = coordinator.executeStep(null, "agent fast empty glue", "", 5);
            assertEquals("FAILED", failed.status(), String.valueOf(failed));
            assertEquals("NOOP_GLUE", failed.error().type());
        } finally {
            System.clearProperty("pkb_noop_glue");
            System.clearProperty("pkb_run_id");
            System.clearProperty("pkb_project");
        }
    }
}
