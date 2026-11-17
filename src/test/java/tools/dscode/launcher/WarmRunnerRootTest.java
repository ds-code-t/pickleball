package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.control.protocol.speed.WarmGeneration;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2.20.0 bug 5: a warm run must point the consumer-runner search at its generation copy. */
class WarmRunnerRootTest {
    private static final String ROOT = ControlProtocol.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY;

    @TempDir
    Path tempDir;

    private WarmGeneration.Snapshot snapshot() {
        Path generation = tempDir.resolve(".pickleball/v/2.20.0/workbench/warm/gen-abc");
        return new WarmGeneration.Snapshot("abc", generation.resolve("classes"), generation.resolve("test-classes"), 0, 0, 0);
    }

    @Test
    void everyWarmRunCarriesTheGenerationTestOutputRoot() {
        WarmGeneration.Snapshot snapshot = snapshot();
        String expected = snapshot.testClasses().toAbsolutePath().normalize().toString();
        List<String> tokens = WorkbenchSpeed.generationProperties(snapshot, List.of("-Dpkb_run_id=r1"));
        assertTrue(tokens.contains("-D" + ROOT + "=" + expected), tokens.toString());
        assertTrue(tokens.contains("-D" + WarmGeneration.TEST_CLASSES_PROPERTY + "=" + expected), tokens.toString());
        assertTrue(tokens.contains("-Dpkb_run_id=r1"), tokens.toString());
    }

    @Test
    void callerSuppliedTestOutputRootWins() {
        List<String> tokens = WorkbenchSpeed.generationProperties(snapshot(), List.of("-D" + ROOT + "=/custom/root"));
        assertEquals(1, tokens.stream().filter(token -> token.startsWith("-D" + ROOT + "=")).count(), tokens.toString());
        assertTrue(tokens.contains("-D" + ROOT + "=/custom/root"), tokens.toString());
    }

    @Test
    void noGenerationMeansNoExtraTokens() {
        assertEquals(List.of("-Dx=1"), WorkbenchSpeed.generationProperties((WarmGeneration.Snapshot) null, List.of("-Dx=1")));
        assertNull(WorkbenchSpeed.warmTestOutputRootArgument(null));
    }

    @Test
    void freshWarmSlotStartsWithTheGenerationTestOutputRoot() {
        WarmGeneration.Snapshot snapshot = snapshot();
        assertEquals(
                "-D" + ROOT + "=" + snapshot.testClasses().toAbsolutePath().normalize(),
                WorkbenchSpeed.warmTestOutputRootArgument(snapshot));
    }

    @Test
    void directRunPinsTheRootUnlessTheRunSuppliedOne() {
        String previous = System.getProperty(ROOT);
        try {
            System.clearProperty(ROOT);
            Path tests = snapshot().testClasses();
            WorkbenchDirectRun.pinTestOutputRoot(tests, List.of());
            assertEquals(tests.toAbsolutePath().normalize().toString(), System.getProperty(ROOT));
            System.setProperty(ROOT, "/custom/root");
            WorkbenchDirectRun.pinTestOutputRoot(tests, List.of("-D" + ROOT + "=/custom/root"));
            assertEquals("/custom/root", System.getProperty(ROOT));
        } finally {
            if (previous == null) System.clearProperty(ROOT);
            else System.setProperty(ROOT, previous);
        }
    }
}
