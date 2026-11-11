package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DaltonEdgeTest {
    @TempDir
    Path project;

    @Test
    void parallelHistoryWritesShareTheWalDatabase() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                int id = index;
                futures.add(pool.submit(() -> DaltonHistory.record(project, new DaltonHistory.Draft(
                        "run-" + id, null, "passed", "", 1, 0, "", "run", "ok-" + id))));
            }
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads, DaltonHistory.list(project).size());
    }

    @Test
    void aHundredFeaturesHydrateIncrementallyAndExtractByteForByte() throws Exception {
        int count = 100;
        List<byte[]> raws = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            byte[] raw = ("Feature: Item " + index + "\n  Scenario: One\n    * step " + index + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            raws.add(raw);
            Path file = project.resolve("features/item-" + index + ".feature");
            Files.createDirectories(file.getParent());
            Files.write(file, raw);
        }
        DaltonHydrator.Result first = DaltonHydrator.hydrate(project, DaltonHydrator.Options.full());
        assertEquals(count, first.inserted());
        assertEquals(0, first.conflicts());
        DaltonHydrator.Result second = DaltonHydrator.hydrate(project, DaltonHydrator.Options.full());
        assertEquals(0, second.inserted());
        assertEquals(count, second.skipped());
        DaltonExtractor.Result extracted = DaltonExtractor.extract(
                project, new DaltonExtractor.Options(List.of(), false));
        assertEquals(0, extracted.written());
        assertEquals(0, extracted.conflicts());
        for (int index = 0; index < count; index++) {
            assertArrayEquals(raws.get(index), Files.readAllBytes(project.resolve("features/item-" + index + ".feature")));
        }
    }
}
