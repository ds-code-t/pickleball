package tools.dscode.parallelutilities;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

public final class Stagger {

    private Stagger() {}

    private enum Global {
        INSTANCE
    }

    public static <R> R call(
            Duration delay,
            Callable<R> work
    ) throws Exception {
        return call(
                Global.INSTANCE,
                delay,
                alwaysAgainst(),
                work
        );
    }

    public static <R> R call(
            Duration delay,
            BooleanSupplier until,
            Callable<R> work
    ) throws Exception {
        return call(
                Global.INSTANCE,
                delay,
                alwaysAgainst(),
                until,
                work
        );
    }

    public static void run(
            Duration delay,
            CheckedRunnable work
    ) throws Exception {
        call(
                delay,
                () -> {
                    work.run();
                    return null;
                }
        );
    }

    public static void run(
            Duration delay,
            BooleanSupplier until,
            CheckedRunnable work
    ) throws Exception {
        call(
                delay,
                until,
                () -> {
                    work.run();
                    return null;
                }
        );
    }

    private static final Object LOCK = new Object();
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final List<Started> STARTED = new ArrayList<>();

    private static final Duration DEFAULT_POLL_EVERY = Duration.ofMillis(250);
    private static final Duration HISTORY_RETENTION = Duration.ofMinutes(10);

    public static <T, R> R call(
            T value,
            Duration delay,
            BiPredicate<T, T> against,
            Callable<R> work
    ) throws Exception {
        return call(value, delay, against, DEFAULT_POLL_EVERY, (current, others) -> true, work);
    }

    public static <T, R> R call(
            T value,
            Duration delay,
            BiPredicate<T, T> against,
            Predicate<T> until,
            Callable<R> work
    ) throws Exception {
        return call(value, delay, against, DEFAULT_POLL_EVERY, (current, others) -> until.test(current), work);
    }

    public static <T, R> R call(
            T value,
            Duration delay,
            BiPredicate<T, T> against,
            BooleanSupplier until,
            Callable<R> work
    ) throws Exception {
        return call(value, delay, against, DEFAULT_POLL_EVERY, (current, others) -> until.getAsBoolean(), work);
    }

    public static <T, R> R call(
            T value,
            Duration delay,
            BiPredicate<T, T> against,
            Duration pollEvery,
            ProceedPredicate<T> until,
            Callable<R> work
    ) throws Exception {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(against, "against");
        Objects.requireNonNull(pollEvery, "pollEvery");
        Objects.requireNonNull(until, "until");
        Objects.requireNonNull(work, "work");

        Object group = groupFor(value);
        Entry self = new Entry(group, value);

        synchronized (LOCK) {
            ENTRIES.add(self);
        }

        try {
            waitUntilReady(self, value, delay, against, pollEvery, until);
            return work.call();
        } finally {
            synchronized (LOCK) {
                ENTRIES.remove(self);
                LOCK.notifyAll();
            }
        }
    }

    public static <T> void run(
            T value,
            Duration delay,
            BiPredicate<T, T> against,
            CheckedRunnable work
    ) throws Exception {
        call(value, delay, against, () -> {
            work.run();
            return null;
        });
    }

    public static <T> void run(
            T value,
            Duration delay,
            BiPredicate<T, T> against,
            Predicate<T> until,
            CheckedRunnable work
    ) throws Exception {
        call(value, delay, against, until, () -> {
            work.run();
            return null;
        });
    }

    public static <T> void run(
            T value,
            Duration delay,
            BiPredicate<T, T> against,
            BooleanSupplier until,
            CheckedRunnable work
    ) throws Exception {
        call(value, delay, against, until, () -> {
            work.run();
            return null;
        });
    }

    public static <T, K> BiPredicate<T, T> sameBy(Function<T, K> keyExtractor) {
        Objects.requireNonNull(keyExtractor, "keyExtractor");

        return (a, b) -> Objects.equals(
                keyExtractor.apply(a),
                keyExtractor.apply(b)
        );
    }

    public static <T> BiPredicate<T, T> alwaysAgainst() {
        return (a, b) -> true;
    }

    private static <T> void waitUntilReady(
            Entry self,
            T value,
            Duration delay,
            BiPredicate<T, T> against,
            Duration pollEvery,
            ProceedPredicate<T> until
    ) throws Exception {
        long delayNanos = delay.toNanos();
        long pollNanos = pollEvery.toNanos();

        while (true) {
            long waitNanos;

            synchronized (LOCK) {
                cleanupHistory();

                List<T> matchingOthers = matchingOthers(self, value, against);

                if (until.canProceed(value, List.copyOf(matchingOthers))) {
                    long now = System.nanoTime();
                    long latestStart = latestMatchingStart(self.group, value, against);

                    long allowedAt = latestStart == Long.MIN_VALUE
                            ? now
                            : latestStart + delayNanos;

                    if (now >= allowedAt) {
                        self.started = true;
                        STARTED.add(new Started(self.group, value, now));
                        LOCK.notifyAll();
                        return;
                    }

                    waitNanos = allowedAt - now;
                } else {
                    waitNanos = pollNanos;
                }
            }

            sleepNanos(waitNanos);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> matchingOthers(
            Entry self,
            T value,
            BiPredicate<T, T> against
    ) {
        List<T> matches = new ArrayList<>();

        for (Entry entry : ENTRIES) {
            if (entry == self) {
                continue;
            }

            if (!Objects.equals(entry.group, self.group)) {
                continue;
            }

            T other = (T) entry.value;

            if (against.test(value, other)) {
                matches.add(other);
            }
        }

        return matches;
    }

    @SuppressWarnings("unchecked")
    private static <T> long latestMatchingStart(
            Object group,
            T value,
            BiPredicate<T, T> against
    ) {
        long latest = Long.MIN_VALUE;

        for (Started started : STARTED) {
            if (!Objects.equals(started.group, group)) {
                continue;
            }

            T other = (T) started.value;

            if (against.test(value, other)) {
                latest = Math.max(latest, started.startNanos);
            }
        }

        return latest;
    }

    private static void cleanupHistory() {
        long cutoff = System.nanoTime() - HISTORY_RETENTION.toNanos();
        STARTED.removeIf(started -> started.startNanos < cutoff);
    }

    private static void sleepNanos(long nanos) throws InterruptedException {
        if (nanos <= 0) {
            return;
        }

        long millis = nanos / 1_000_000;
        int extraNanos = (int) (nanos % 1_000_000);

        Thread.sleep(millis, extraNanos);
    }

    private static Object groupFor(Object value) {
        return value == null ? NullValue.class : value.getClass();
    }

    private static final class NullValue {}

    private static final class Entry {
        private final Object group;
        private final Object value;
        private boolean started;

        private Entry(Object group, Object value) {
            this.group = group;
            this.value = value;
        }
    }

    private static final class Started {
        private final Object group;
        private final Object value;
        private final long startNanos;

        private Started(Object group, Object value, long startNanos) {
            this.group = group;
            this.value = value;
            this.startNanos = startNanos;
        }
    }

    @FunctionalInterface
    public interface ProceedPredicate<T> {
        boolean canProceed(T current, List<T> matchingOthers) throws Exception;
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }
}