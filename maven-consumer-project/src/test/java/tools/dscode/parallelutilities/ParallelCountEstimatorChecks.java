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
    void estimateIgnoresAProjectBrowserFile() throws Exception {
        int before = ParallelCountEstimator.estimate();
        Path project = Files.createTempDirectory("pkb-estimate-");
        try {
            Path resources = project.resolve("src/test/resources");
            Files.createDirectories(resources);
            Files.writeString(resources.resolve("pickleball.properties"), "pkb_browser=GRID_EDGE\n");
            assertEquals(before, ParallelCountEstimator.estimate());
            assertEquals(before, ParallelCountEstimator.resolve("auto"));
            assertEquals(before, ParallelCountEstimator.resolve(" AUTO "));
            assertTrue(ParallelCountEstimator.isAuto("auto"));
            assertFalse(ParallelCountEstimator.isAuto("GRID_EDGE"));
            assertTrue(before >= 2);
            assertTrue(before <= 24);
            assertFalse(Integer.toString(before).equals("auto"));
        } finally {
            deleteTree(project);
        }
    }

    @Test
    void resolveRejectsABrowserNameInsteadOfBuildingRunVars() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ParallelCountEstimator.resolve("CHROME_HEADLESS")
        );
        assertTrue(failure.getMessage().contains("pkb_parallel"));
        assertTrue(failure.getMessage().contains("CHROME_HEADLESS"));
        assertThrows(IllegalArgumentException.class, () -> ParallelCountEstimator.resolve("GRID_EDGE"));
        assertEquals(ParallelCountEstimator.estimate(), ParallelCountEstimator.resolve("auto"));
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
