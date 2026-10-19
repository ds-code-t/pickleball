package tools.dscode.control.protocol;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Session heartbeat that notices a machine sleep or a stall.
 *
 * <p>The session thread wakes every {@link #INTERVAL_MS}. A wall-clock jump
 * between beats that is longer than {@link #THRESHOLD_MS} is one sleep-gap.
 * A Gherkin step whose text says {@code sleep}, and a short gap between
 * scenario events, are not sleep-gaps.</p>
 */
public final class SleepGapMonitor implements AutoCloseable {
    public static final long INTERVAL_MS = 5_000L;
    public static final long THRESHOLD_MS = 30_000L;

    public record Gap(Instant detectedAt, long gapMs) {
    }

    private final long intervalMs;
    private final long thresholdMs;
    private final List<Gap> gaps = new ArrayList<>();
    private long previousMillis = Long.MIN_VALUE;
    private Thread thread;
    private volatile boolean stopped;

    public SleepGapMonitor() {
        this(INTERVAL_MS, THRESHOLD_MS);
    }

    public SleepGapMonitor(long intervalMs, long thresholdMs) {
        if (intervalMs <= 0 || thresholdMs <= 0) {
            throw new IllegalArgumentException("sleep-gap interval and threshold must be positive");
        }
        this.intervalMs = intervalMs;
        this.thresholdMs = thresholdMs;
    }

    /**
     * One heartbeat at {@code epochMillis}. The first beat only starts the
     * clock. A later beat records a gap when the wall delta exceeds the threshold.
     */
    public synchronized void beat(long epochMillis) {
        if (previousMillis != Long.MIN_VALUE) {
            long delta = epochMillis - previousMillis;
            if (delta > thresholdMs) {
                gaps.add(new Gap(Instant.ofEpochMilli(epochMillis), delta));
            }
        }
        previousMillis = epochMillis;
    }

    public synchronized List<Gap> gaps() {
        return List.copyOf(gaps);
    }

    public synchronized void start() {
        if (thread != null) return;
        stopped = false;
        thread = new Thread(this::loop, "pickleball-sleep-gap");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        beat(System.currentTimeMillis());
        while (!stopped) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (stopped) return;
            beat(System.currentTimeMillis());
        }
    }

    @Override
    public void close() {
        stopped = true;
        Thread current = thread;
        if (current != null) current.interrupt();
    }
}
