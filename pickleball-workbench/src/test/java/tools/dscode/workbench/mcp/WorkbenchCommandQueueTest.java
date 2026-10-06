package tools.dscode.workbench.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchCommandQueueTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void ackThenDoneAndSerialExecuteStep() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        try (WorkbenchCommandQueue queue = new WorkbenchCommandQueue(text -> {
            if ("slow".equals(text)) {
                firstStarted.countDown();
                await(hold);
            }
            return Map.of("status", "SUCCESS", "text", text);
        })) {
            Map<String, Object> first = queue.enqueueExecuteStep("slow", "one");
            assertEquals(true, first.get("ack"));
            assertEquals("one", first.get("id"));
            assertTrue(first.get("status").equals("QUEUED") || first.get("status").equals("RUNNING"));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            assertEquals("STILL_WORKING", queue.status("one").get("status"));

            Map<String, Object> second = queue.enqueueExecuteStep("fast", "two");
            assertEquals("two", second.get("id"));
            assertEquals("QUEUED", second.get("status"));

            hold.countDown();
            assertEquals("SUCCESS", awaitStatus(queue, "one", "SUCCESS"));
            assertEquals("SUCCESS", awaitStatus(queue, "two", "SUCCESS"));
            assertEquals("slow", ((Map<?, ?>) queue.status("one").get("result")).get("text"));
            assertEquals("fast", ((Map<?, ?>) queue.status("two").get("result")).get("text"));
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void enqueueOpUsesTheSharedHandler() throws Exception {
        try (WorkbenchCommandQueue queue = new WorkbenchCommandQueue(text -> {
            throw new AssertionError("execute-step function is not used once an op handler is installed");
        })) {
            queue.setOpHandler((op, args) -> Map.of("status", "SUCCESS", "op", op, "example", args.get("example")));
            Map<String, Object> ack = queue.enqueueOp("example", Map.of("example", "2.2"), "row");
            assertEquals("row", ack.get("id"));
            assertEquals("SUCCESS", awaitStatus(queue, "row", "SUCCESS"));
            assertEquals("2.2", ((Map<?, ?>) queue.status("row").get("result")).get("example"));
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void stillWorkingWhileSlowToolUntilHeartbeatIsSilenced() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try (WorkbenchCommandQueue queue = new WorkbenchCommandQueue(text -> {
            started.countDown();
            await(hold);
            return Map.of("status", "SUCCESS");
        }, Duration.ofMillis(80))) {
            queue.enqueueExecuteStep("wait-here", "slow-id");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals("STILL_WORKING", queue.status("slow-id").get("status"));

            queue.silenceHeartbeat();
            Thread.sleep(120);
            assertEquals("TIMEOUT", queue.status("slow-id").get("status"));
            hold.countDown();
        }
    }

    private static String awaitStatus(WorkbenchCommandQueue queue, String id, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        String last = "";
        while (System.nanoTime() < deadline) {
            last = String.valueOf(queue.status(id).get("status"));
            if (expected.equals(last)) return last;
            Thread.sleep(20);
        }
        return last;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
