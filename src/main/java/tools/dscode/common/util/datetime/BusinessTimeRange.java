package tools.dscode.common.util.datetime;

import java.math.BigInteger;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Parsed weekly time-range value.
 *
 * A range such as "MON 22:00-02:00" is preserved as one logical overnight segment.
 * Calendar materialization expands that segment across the date boundary when needed.
 */
public final class BusinessTimeRange {

    public static final long NANOS_PER_DAY = 86_400L * 1_000_000_000L;

    private final String originalInput;
    private final List<Segment> segments;

    BusinessTimeRange(String originalInput, List<Segment> segments) {
        this.originalInput = originalInput;
        this.segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        if (this.segments.isEmpty()) {
            throw new IllegalArgumentException("Time range must contain at least one segment");
        }
    }

    /** Parses a weekly time-range expression. If days are omitted, the range applies every day. */
    public static BusinessTimeRange parse(String spec) {
        return BusinessCalendar.parseTimeRange(spec);
    }

    public String originalInput() { return originalInput; }
    public List<Segment> segments() { return segments; }

    /** Total range duration across all selected days and segments. */
    public Duration totalDuration() {
        return Duration.ofNanos(totalNanos().longValueExact());
    }

    /** Total range duration in nanoseconds across all selected days and segments. */
    public BigInteger totalNanos() {
        BigInteger total = BigInteger.ZERO;
        for (Segment s : segments) {
            total = total.add(BigInteger.valueOf(s.durationNanos()).multiply(BigInteger.valueOf(s.days().size())));
        }
        return total;
    }

    public String toCanonicalString() {
        return segments.stream().map(Segment::toCanonicalString).collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return toCanonicalString();
    }

    public static final class Segment {
        private final EnumSet<DayOfWeek> days;
        private final long startNanoOfDay;
        private final long endNanoOfDay;

        public Segment(EnumSet<DayOfWeek> days, long startNanoOfDay, long endNanoOfDay) {
            if (days == null || days.isEmpty()) throw new IllegalArgumentException("Segment days must not be empty");
            if (startNanoOfDay < 0 || startNanoOfDay > NANOS_PER_DAY) throw new IllegalArgumentException("Invalid start nanos-of-day: " + startNanoOfDay);
            if (endNanoOfDay < 0 || endNanoOfDay > NANOS_PER_DAY) throw new IllegalArgumentException("Invalid end nanos-of-day: " + endNanoOfDay);
            if (startNanoOfDay == endNanoOfDay) throw new IllegalArgumentException("Zero-length time ranges are not supported");
            this.days = EnumSet.copyOf(days);
            this.startNanoOfDay = startNanoOfDay;
            this.endNanoOfDay = endNanoOfDay;
        }

        public EnumSet<DayOfWeek> days() { return EnumSet.copyOf(days); }
        public long startNanoOfDay() { return startNanoOfDay; }
        public long endNanoOfDay() { return endNanoOfDay; }
        public boolean overnight() { return endNanoOfDay < startNanoOfDay; }

        public long durationNanos() {
            if (endNanoOfDay > startNanoOfDay) return endNanoOfDay - startNanoOfDay;
            return (NANOS_PER_DAY - startNanoOfDay) + endNanoOfDay;
        }

        public String toCanonicalString() {
            return formatDays(days) + " " + formatNanoOfDay(startNanoOfDay) + "-" + formatNanoOfDay(endNanoOfDay);
        }
    }

    private static String formatDays(EnumSet<DayOfWeek> days) {
        List<String> names = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            if (days.contains(d)) names.add(d.name().substring(0, 3));
        }
        return String.join(",", names);
    }

    private static String formatNanoOfDay(long nanoOfDay) {
        if (nanoOfDay == NANOS_PER_DAY) return "24:00";
        LocalTime t = LocalTime.ofNanoOfDay(nanoOfDay);
        String base = String.format(Locale.ROOT, "%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
        if (t.getNano() == 0) return base;
        String frac = String.format(Locale.ROOT, "%09d", t.getNano()).replaceFirst("0+$", "");
        return base + "." + frac;
    }
}
