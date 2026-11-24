package tools.dscode.common.agentfast;

import java.util.Locale;

/**
 * E2b push wake for the collaboration waits ({@code answer-wait}, {@code ask-and-wait}, {@code ui-poll},
 * {@code chat-poll --wait}, {@code chat-turn}). A panel binding delivery, a stored overlay input or a chat send
 * signals here, and every waiter wakes at once instead of sleeping out its poll interval. Waiters keep a long
 * backstop for pages without a binding (queued inputs that only a drain reads), so a wait never hangs.
 * {@code pkb_push_wake=off} restores the old fixed sleeps.
 */
public final class PushWake {
    /** Longest a push waiter sleeps before it re-checks anyway. */
    public static final long BACKSTOP_MS = 250;
    private static final Object LOCK = new Object();
    private static long generation;
    private static volatile long lastSignalNanos;

    private PushWake() {
    }

    public static boolean enabled() {
        String value = tools.dscode.browser.BrowserProtocols.setting("pkb_push_wake");
        if (value == null || value.isBlank()) return true;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return !(v.equals("off") || v.equals("false") || v.equals("no") || v.equals("0"));
    }

    /** "push" or "poll", for replies. */
    public static String mode() {
        return enabled() ? "push" : "poll";
    }

    public static long generation() {
        synchronized (LOCK) {
            return generation;
        }
    }

    /** Something a waiter may be waiting for has arrived. */
    public static void signal() {
        synchronized (LOCK) {
            generation++;
            lastSignalNanos = System.nanoTime();
            LOCK.notifyAll();
        }
    }

    public static long lastSignalNanos() {
        return lastSignalNanos;
    }

    /**
     * Waits until a signal newer than {@code seen} or until {@code pollMs} (poll mode) / {@link #BACKSTOP_MS}
     * (push mode) passes, but never past {@code deadlineNanos}. Returns the current generation.
     */
    public static long await(long seen, long pollMs, long deadlineNanos) throws InterruptedException {
        long cap = enabled() ? BACKSTOP_MS : pollMs;
        long left = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        long wait = Math.max(1, Math.min(cap, left));
        if (!enabled()) {
            Thread.sleep(wait);
            return generation();
        }
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(wait);
        synchronized (LOCK) {
            while (generation == seen) {
                long ms = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime());
                if (ms <= 0) break;
                LOCK.wait(ms);
            }
            return generation;
        }
    }
}
