package tools.dscode.parallelutilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelCountEstimatorTest {
    @TempDir
    Path tempDir;

    @Test
    void tinyHeapYieldsMinimumWorkers() {
        assertEquals(2, ParallelCountEstimator.estimate(8, 256L * 1024 * 1024));
        assertEquals(2, ParallelCountEstimator.estimate(32, 512L * 1024 * 1024));
    }

    @Test
    void manyCoresAndLargeHeapAreCapped() {
        long sixtyFourGib = 64L * 1024 * 1024 * 1024;
        assertEquals(24, ParallelCountEstimator.estimate(32, sixtyFourGib));
        assertEquals(24, ParallelCountEstimator.estimate(128, sixtyFourGib));
    }

    @Test
    void memoryCanCapBelowCoreCountBeforeTheHardCap() {
        assertEquals(4, ParallelCountEstimator.estimate(16, 2048L * 1024 * 1024));
    }

    @Test
    void explicitNumericIsNotOverwritten() {
        assertEquals(7, ParallelCountEstimator.resolve("7"));
        assertEquals(1, ParallelCountEstimator.resolve("1"));
    }

    @Test
    void autoResolvesToTheLiveEstimate() {
        assertEquals(ParallelCountEstimator.estimate(), ParallelCountEstimator.resolve("auto"));
        assertEquals(ParallelCountEstimator.estimate(), ParallelCountEstimator.resolve("AUTO"));
        assertTrue(ParallelCountEstimator.isAuto(" auto "));
        assertFalse(ParallelCountEstimator.isAuto("8"));
    }

    @Test
    void invalidParallelValuesFailClearly() {
        assertThrows(IllegalArgumentException.class, () -> ParallelCountEstimator.resolve("nope"));
        assertThrows(IllegalArgumentException.class, () -> ParallelCountEstimator.resolve("0"));
        assertThrows(IllegalArgumentException.class, () -> ParallelCountEstimator.resolve("-3"));
    }

    @Test
    void recommendedDiscoverRunVarsIncludeEstimatedParallel() {
        String runVars = ParallelCountEstimator.recommendedDiscoverRunVars();
        assertTrue(runVars.contains("pkb_browser="));
        assertTrue(runVars.contains("pkb_parallel=" + ParallelCountEstimator.estimate()));
        assertTrue(runVars.contains("pkb_reportingmode=diagnostic"));
        assertTrue(runVars.contains("pkb_loglevel=warn"));
        assertTrue(runVars.contains("pkb_reportretention=failed"));
        assertFalse(runVars.contains("pkb_parallel=80"));
        assertFalse(runVars.contains("pkb_parallel=auto"));
    }

    @Test
    void recommendedDiscoverRunVarsUseBrowserLadder() throws Exception {
        Path resources = tempDir.resolve("src/test/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("pickleball.properties"), "pkb_browser=SAUCE_CHROME\n");

        String runVars = ParallelCountEstimator.recommendedDiscoverRunVars(tempDir);
        assertTrue(runVars.contains("pkb_browser=SAUCE_CHROME"));
        assertFalse(runVars.contains("pkb_browser=CHROME_HEADLESS"));
    }
}
