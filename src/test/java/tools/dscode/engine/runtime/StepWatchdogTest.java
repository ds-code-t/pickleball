package tools.dscode.engine.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepWatchdogTest {
    @Test
    void blockedStepIsFailedWithoutInterruptingTheThread() throws Exception {
        StepWatchdog.sampleMillis = 200;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Instant started = Instant.now();
        StepWatchdog.Sample sample = new StepWatchdog.Sample() {
            @Override
            public String scenario() {
                return "watchdog";
            }

            @Override
            public Duration cap() {
                return Duration.ofSeconds(1);
            }

            @Override
            public Instant started() {
                return started;
            }

            @Override
            public void fail(String message) {
                failure.compareAndSet(null, message);
            }
        };
        Thread body = new Thread(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        StepWatchdog watchdog = StepWatchdog.start(null, sample);
        body.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_000);
            while (failure.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            String message = failure.get();
            assertNotNull(message, "watchdog did not fire");
            assertTrue(message.contains("error: step exceeded its time cap"), message);
            assertTrue(message.contains("fix: raise timeout_seconds or pkb_stepMaxTime"), message);
            assertTrue(body.isAlive());
            assertFalse(body.isInterrupted());
        } finally {
            release.countDown();
            body.join(2_000);
            watchdog.close();
            StepWatchdog.sampleMillis = StepWatchdog.PRODUCTION_SAMPLE_MS;
        }
    }
}
