package tools.dscode.store;

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

class StoreEdgeTest {
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
                futures.add(pool.submit(() -> StoreHistory.record(project, new StoreHistory.Draft(
                        "run-" + id, null, "passed", "", 1, 0, "", "run", "ok-" + id))));
            }
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads, StoreHistory.list(project).size());
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
        StoreHydrator.Result first = StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        assertEquals(count, first.inserted());
        assertEquals(0, first.conflicts());
        StoreHydrator.Result second = StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        assertEquals(0, second.inserted());
        assertEquals(count, second.skipped());
        StoreExtractor.Result extracted = StoreExtractor.extract(
                project, new StoreExtractor.Options(List.of(), false));
        assertEquals(0, extracted.written());
        assertEquals(0, extracted.conflicts());
        for (int index = 0; index < count; index++) {
            assertArrayEquals(raws.get(index), Files.readAllBytes(project.resolve("features/item-" + index + ".feature")));
        }
    }
}
