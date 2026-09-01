package tools.dscode.parallelutilities;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ParallelCountEstimatorChecks {
    @Test
    void tinyHeapYieldsMinimumWorkers() {
        assertEquals(2, ParallelCountEstimator.estimate(4, 128L * 1024 * 1024));
        assertEquals(2, ParallelCountEstimator.estimate(8, 256L * 1024 * 1024));
    }

    @Test
    void manyCoresAndLargeHeapAreCappedAtTwentyFour() {
        assertEquals(24, ParallelCountEstimator.estimate(32, 64L * 1024 * 1024 * 1024));
        assertEquals(24, ParallelCountEstimator.estimate(128, 64L * 1024 * 1024 * 1024));
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
        assertEquals(ParallelCountEstimator.estimate(), ParallelCountEstimator.resolve(" AUTO "));
        assertTrue(ParallelCountEstimator.isAuto("auto"));
        assertFalse(ParallelCountEstimator.isAuto("8"));
    }

    @Test
    void invalidParallelValuesFailClearly() {
        assertThrows(IllegalArgumentException.class, () -> ParallelCountEstimator.resolve("nope"));
        assertThrows(IllegalArgumentException.class, () -> ParallelCountEstimator.resolve("0"));
    }

    @Test
    void recommendedDiscoverRunVarsIncludeEstimatedParallel() {
        String runVars = ParallelCountEstimator.recommendedDiscoverRunVars();
        assertTrue(runVars.contains("pkb_browser="));
        assertTrue(runVars.contains("pkb_parallel=" + ParallelCountEstimator.estimate()));
        assertTrue(runVars.contains("pkb_reportingmode=diagnostic"));
        assertFalse(runVars.contains("pkb_parallel=80"));
        assertFalse(runVars.contains("pkb_parallel=auto"));
    }

    @Test
    void recommendedDiscoverRunVarsUseBrowserLadder() throws Exception {
        Path project = Files.createTempDirectory("pkb-discover-runvars-");
        try {
            Path resources = project.resolve("src/test/resources");
            Files.createDirectories(resources);
            Files.writeString(resources.resolve("pickleball.properties"), "pkb_browser=GRID_EDGE\n");
            String runVars = ParallelCountEstimator.recommendedDiscoverRunVars(project);
            assertTrue(runVars.contains("pkb_browser=GRID_EDGE"));
            assertFalse(runVars.contains("pkb_browser=CHROME_HEADLESS"));
        } finally {
            deleteTree(project);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
