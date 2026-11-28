package tools.dscode.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.EngineLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineLogConcurrencyTest {
    @TempDir
    Path project;

    @Test
    void concurrentAppendsKeepEveryLine() throws Exception {
        String previous = System.setProperty("pkb_engine_log", "all");
        String previousCap = System.getProperty("pkb_engine_log_max_bytes");
        System.clearProperty("pkb_engine_log_max_bytes");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            int threads = 8;
            int lines = 40;
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                int id = thread;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int line = 0; line < lines; line++) {
                        EngineLog.log(project, "match", "worker-" + id, "line-" + id + "-" + line);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(60, TimeUnit.SECONDS);
            String text = Files.readString(project.resolve(".pickleball/engine/engine.log"));
            for (int thread = 0; thread < threads; thread++) {
                for (int line = 0; line < lines; line++) {
                    assertTrue(text.contains("line-" + thread + "-" + line), "missing line-" + thread + "-" + line);
                }
            }
        } finally {
            pool.shutdownNow();
            if (previous == null) System.clearProperty("pkb_engine_log");
            else System.setProperty("pkb_engine_log", previous);
            if (previousCap == null) System.clearProperty("pkb_engine_log_max_bytes");
            else System.setProperty("pkb_engine_log_max_bytes", previousCap);
        }
    }
}
