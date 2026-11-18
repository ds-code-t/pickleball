package tools.dscode.control.protocol;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Notices a JDK {@code HttpClient} selector thread spinning.
 * The worker's selector has been seen stuck in a loop on heavy pages.
 * A high CPU share across one window marks this watchdog unhealthy so
 * {@code /v1/health} can fail immediately and the controller can start a new worker.
 */
public final class SelectorWatchdog {
    public static final Duration DEFAULT_WINDOW = Duration.ofSeconds(15);
    public static final int DEFAULT_CPU_PERCENT = 80;
    public static final String SELECTOR_NAME = "SelectorManager";
    public static final String SPIN_REASON = "selector-spin";

    private final long windowNanos;
    private final int cpuPercent;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile boolean stuck;
    private volatile String reason = "";
    private long windowStartNanos = Long.MIN_VALUE;
    private long windowStartCpu = 0L;
    private Thread thread;

    public SelectorWatchdog(Duration window, int cpuPercent) {
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Selector watchdog window must be positive.");
        }
        if (cpuPercent < 1 || cpuPercent > 100) {
            throw new IllegalArgumentException("Selector watchdog CPU percent must be from 1 to 100.");
        }
        this.windowNanos = window.toNanos();
        this.cpuPercent = cpuPercent;
    }

    public static SelectorWatchdog startDefault() {
        SelectorWatchdog watchdog = new SelectorWatchdog(DEFAULT_WINDOW, DEFAULT_CPU_PERCENT);
        watchdog.start();
        return watchdog;
    }

    /**
     * Absolute clock and cumulative selector CPU, both in nanoseconds.
     * The first call is the baseline. A later call trips when the window has
     * elapsed and the selector used at least the configured percent of that window.
     */
    public void observe(long nowNanos, long selectorCpuNanos) {
        if (stuck) return;
        if (windowStartNanos == Long.MIN_VALUE) {
            windowStartNanos = nowNanos;
            windowStartCpu = selectorCpuNanos;
            return;
        }
        long elapsed = nowNanos - windowStartNanos;
        long cpu = selectorCpuNanos - windowStartCpu;
        if (cpu < 0) {
            windowStartNanos = nowNanos;
            windowStartCpu = selectorCpuNanos;
            return;
        }
        if (elapsed < windowNanos) return;
        if (cpu * 100L >= elapsed * cpuPercent) {
            stuck = true;
            reason = SPIN_REASON;
            return;
        }
        windowStartNanos = nowNanos;
        windowStartCpu = selectorCpuNanos;
    }

    public boolean unhealthy() {
        return stuck;
    }

    public String reason() {
        return reason;
    }

    public void start() {
        if (thread != null) return;
        Thread sampler = new Thread(this::sample, "pickleball-selector-watchdog");
        sampler.setDaemon(true);
        thread = sampler;
        sampler.start();
    }

    public void stop() {
        stopped.set(true);
        Thread sampler = thread;
        if (sampler != null) sampler.interrupt();
    }

    private void sample() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean.isThreadCpuTimeSupported() && !bean.isThreadCpuTimeEnabled()) {
            try {
                bean.setThreadCpuTimeEnabled(true);
            } catch (SecurityException ignored) {
                // Without CPU time the watchdog stays healthy instead of guessing.
            }
        }
        while (!stopped.get()) {
            observe(System.nanoTime(), selectorCpu(bean));
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static long selectorCpu(ThreadMXBean bean) {
        if (bean == null || !bean.isThreadCpuTimeSupported()) return 0L;
        long total = 0L;
        for (Thread candidate : Thread.getAllStackTraces().keySet()) {
            String name = candidate.getName();
            if (name == null || !name.contains(SELECTOR_NAME)) continue;
            long cpu = bean.getThreadCpuTime(candidate.threadId());
            if (cpu > 0L) total += cpu;
        }
        return total;
    }
}
