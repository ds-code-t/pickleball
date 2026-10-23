package io.cucumber.core.runner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-thread section clock for a block {@code until}. Inactive unless
 * {@link #start()} was called on this thread, so a normal run only pays a
 * thread-local read. The clock does not change pass semantics.
 */
public final class UntilPassTiming {
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    private static final AtomicReference<String> LAST_REPORT =
            new AtomicReference<>("until-pass timing inactive");
    private static final AtomicReference<Session> LAST_SESSION = new AtomicReference<>();

    private UntilPassTiming() {
    }

    /** System property {@code pickleball.until.passTiming=true} arms the next until. */
    public static boolean requested() {
        return Boolean.getBoolean("pickleball.until.passTiming");
    }

    public static void start() {
        CURRENT.set(new Session());
    }

    public static void stop() {
        CURRENT.remove();
    }

    /** Copies this thread's clock so a caller can read it after {@link #stop()}. */
    public static void publish() {
        Session session = CURRENT.get();
        if (session == null) {
            LAST_REPORT.set("until-pass timing inactive");
            LAST_SESSION.set(null);
            return;
        }
        LAST_REPORT.set(session.report());
        LAST_SESSION.set(session);
    }

    public static String lastReport() {
        return LAST_REPORT.get();
    }

    public static long lastPasses() {
        Session session = LAST_SESSION.get();
        return session == null ? 0 : session.passCount;
    }

    public static double lastExclusiveMillisPerPass(String section) {
        Session session = LAST_SESSION.get();
        if (session == null || session.passCount == 0) return 0;
        return session.exclusive.getOrDefault(section, 0L) / 1_000_000d / session.passCount;
    }

    public static double lastInclusiveMillisPerPass(String section) {
        Session session = LAST_SESSION.get();
        if (session == null || session.passCount == 0) return 0;
        return session.inclusive.getOrDefault(section, 0L) / 1_000_000d / session.passCount;
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** Inclusive and exclusive nanos for the sections closed on this thread. */
    public static String report() {
        Session session = CURRENT.get();
        return session == null ? "until-pass timing inactive" : session.report();
    }

    public static long passes() {
        Session session = CURRENT.get();
        return session == null ? 0 : session.passCount;
    }

    /** Mean exclusive milliseconds of one section across the recorded passes. */
    public static double exclusiveMillisPerPass(String section) {
        Session session = CURRENT.get();
        if (session == null || session.passCount == 0) return 0;
        return session.exclusive.getOrDefault(section, 0L) / 1_000_000d / session.passCount;
    }

    public static double inclusiveMillisPerPass(String section) {
        Session session = CURRENT.get();
        if (session == null || session.passCount == 0) return 0;
        return session.inclusive.getOrDefault(section, 0L) / 1_000_000d / session.passCount;
    }

    public static Scope open(String section) {
        Session session = CURRENT.get();
        if (session == null) return Scope.NOOP;
        return session.open(section);
    }

    public static final class Scope implements AutoCloseable {
        static final Scope NOOP = new Scope(null, null);

        private final Session session;
        private final Frame frame;

        private Scope(Session session, Frame frame) {
            this.session = session;
            this.frame = frame;
        }

        @Override
        public void close() {
            if (session != null) session.close(frame);
        }
    }

    private static final class Frame {
        final String name;
        final long start = System.nanoTime();
        final Frame parent;
        long childNanos;

        Frame(String name, Frame parent) {
            this.name = name;
            this.parent = parent;
        }
    }

    private static final class Session {
        private Frame top;
        private long passCount;
        private final Map<String, Long> exclusive = new LinkedHashMap<>();
        private final Map<String, Long> inclusive = new LinkedHashMap<>();
        private final Map<String, Integer> counts = new LinkedHashMap<>();

        Scope open(String section) {
            Frame frame = new Frame(section, top);
            top = frame;
            return new Scope(this, frame);
        }

        void close(Frame frame) {
            long elapsed = System.nanoTime() - frame.start;
            if (frame.parent != null) frame.parent.childNanos += elapsed;
            exclusive.merge(frame.name, Math.max(0, elapsed - frame.childNanos), Long::sum);
            inclusive.merge(frame.name, elapsed, Long::sum);
            counts.merge(frame.name, 1, Integer::sum);
            if ("until-pass".equals(frame.name)) passCount++;
            top = frame.parent;
        }

        String report() {
            StringBuilder text = new StringBuilder();
            text.append("until-passes=").append(passCount);
            List<String> names = new ArrayList<>();
            names.addAll(inclusive.keySet());
            for (String name : exclusive.keySet()) {
                if (!names.contains(name)) names.add(name);
            }
            for (String name : names) {
                long inc = inclusive.getOrDefault(name, 0L);
                long exc = exclusive.getOrDefault(name, 0L);
                int count = counts.getOrDefault(name, 0);
                double per = passCount == 0 ? 0 : exc / 1_000_000d / passCount;
                double incPer = passCount == 0 ? 0 : inc / 1_000_000d / passCount;
                text.append(String.format(
                        Locale.ROOT,
                        "%n  %s count=%d inclusiveMs=%.1f exclusiveMs=%.1f exclusivePerPassMs=%.1f inclusivePerPassMs=%.1f",
                        name,
                        count,
                        inc / 1_000_000d,
                        exc / 1_000_000d,
                        per,
                        incPer
                ));
            }
            return text.toString();
        }
    }
}
