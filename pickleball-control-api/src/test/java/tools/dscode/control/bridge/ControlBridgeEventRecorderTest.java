package tools.dscode.control.bridge;

import org.junit.jupiter.api.Test;
import tools.dscode.control.protocol.ControlBridgeEvent;
import tools.dscode.control.protocol.ControlBridgeEventPage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlBridgeEventRecorderTest {
    @Test
    void executedStepIsOnTheSameEventStreamAsHooks() {
        ControlBridgeEventRecorder recorder = new ControlBridgeEventRecorder();
        recorder.recordExecutedStep("scene-1", "Named", "INFO: harness step", "SUCCESS");
        ControlBridgeEventPage page = recorder.page(null, 0L, 20);
        assertEquals(1, page.events().size());
        assertEquals("INFO: harness step", page.events().getFirst().stepText());
        assertEquals("EXECUTE_STEP", page.events().getFirst().hook());
        assertTrue(page.events().getFirst().signature().contains("SUCCESS"));
    }

    @Test
    void concurrentAppendsStayInIncreasingSequenceOrder() throws Exception {
        ControlBridgeEventRecorder recorder = new ControlBridgeEventRecorder();
        int threads = 16;
        int each = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int thread = 0; thread < threads; thread++) {
            int id = thread;
            pool.submit(() -> {
                start.await();
                for (int index = 0; index < each; index++) {
                    recorder.recordExecutedStep("scene", "Named", "step-" + id + "-" + index, "SUCCESS");
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        ControlBridgeEventPage page = recorder.page(null, 0L, 500);
        assertEquals(threads * each, page.events().size());
        long previous = 0;
        for (ControlBridgeEvent event : page.events()) {
            assertTrue(event.sequence() > previous, "sequence " + event.sequence() + " after " + previous);
            previous = event.sequence();
        }
    }
}