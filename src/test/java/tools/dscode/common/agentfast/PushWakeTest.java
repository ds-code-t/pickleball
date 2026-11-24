package tools.dscode.common.agentfast;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushWakeTest {
    @AfterEach
    void clear() {
        System.clearProperty("pkb_push_wake");
    }

    @Test
    void aSignalWakesAWaiterBeforeItsBackstop() throws Exception {
        long seen = PushWake.generation();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        CompletableFuture<Long> woke = CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            try {
                PushWake.await(seen, 5_000, deadline);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        });
        Thread.sleep(60);
        long signalled = System.nanoTime();
        PushWake.signal();
        long waited = woke.get(5, TimeUnit.SECONDS);
        long afterSignal = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - signalled);
        assertTrue(afterSignal < PushWake.BACKSTOP_MS, "woke " + afterSignal + " ms after the signal");
        assertTrue(waited < PushWake.BACKSTOP_MS + 60, "waited " + waited);
        assertEquals("push", PushWake.mode());
    }

    @Test
    void offRestoresTheFixedPollAndReportsPoll() throws Exception {
        System.setProperty("pkb_push_wake", "off");
        assertEquals("poll", PushWake.mode());
        long start = System.nanoTime();
        PushWake.await(PushWake.generation(), 120, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 100, "poll mode sleeps its interval");
    }

    @Test
    void snapDeltaIsPollWhenNoPushChannelExists() {
        assertEquals("poll", SnapPush.delta("missing-cursor", null).get("mode"));
        assertTrue(!SnapPush.ensure(null, true));
    }
}
