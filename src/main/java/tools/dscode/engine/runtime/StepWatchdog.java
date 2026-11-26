package tools.dscode.engine.runtime;

import tools.dscode.engine.EngineLog;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Fails a hung non-repeating step without interrupting its thread.
 * Samples every 60 seconds unless a test sets {@link #sampleMillis}.
 * A repeating {@code until} keeps the cap inside its own loop.
 */
public final class StepWatchdog {
    static final long PRODUCTION_SAMPLE_MS = 60_000L;
    static volatile long sampleMillis = PRODUCTION_SAMPLE_MS;
    private static final java.util.concurrent.atomic.AtomicInteger REPEATING =
            new java.util.concurrent.atomic.AtomicInteger();

    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Thread thread;

    private StepWatchdog(Thread thread) {
        this.thread = thread;
    }

    public interface Sample {
        String scenario();

        Duration cap();

        Instant started();

        void fail(String message);
    }

    public static void markRepeating(boolean repeating) {
        if (repeating) {
            REPEATING.incrementAndGet();
        } else {
            REPEATING.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    public static boolean repeatingNow() {
        return REPEATING.get() > 0;
    }

    public static StepWatchdog start(Path project, Sample sample) {
        Thread thread = new Thread(() -> loop(project, sample), "pickleball-step-watchdog");
        thread.setDaemon(true);
        StepWatchdog watchdog = new StepWatchdog(thread);
        thread.start();
        return watchdog;
    }

    public void close() {
        stopped.set(true);
        thread.interrupt();
    }

    private static void loop(Path project, Sample sample) {
        while (!Thread.currentThread().isInterrupted()) {
            if (!sleep()) return;
            String scenario = sample.scenario() == null || sample.scenario().isBlank()
                    ? "scenario" : sample.scenario();
            boolean repeating = REPEATING.get() > 0;
            EngineLog.log(project, "watchdog", scenario, repeating ? "sample repeating" : "sample");
            if (repeating) continue;
            Duration cap = sample.cap();
            Instant started = sample.started();
            if (cap == null || cap.isNegative() || started == null) continue;
            if (Duration.between(started, Instant.now()).compareTo(cap) > 0) {
                String message = "error: step exceeded its time cap\nfix: raise timeout_seconds or pkb_stepMaxTime";
                EngineLog.log(project, "watchdog", scenario, "fire " + message.replace('\n', ' '));
                sample.fail(message);
            }
        }
    }

    private static boolean sleep() {
        try {
            Thread.sleep(sampleMillis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
