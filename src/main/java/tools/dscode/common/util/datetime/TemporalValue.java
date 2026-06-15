package tools.dscode.common.util.datetime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolved temporal value that preserves both the original input and the parsed object.
 *
 * DATE_TIME values wrap BusinessTime/ZonedDateTime.
 * DURATION values wrap java.time.Duration.
 * TIME_RANGE values wrap a parsed weekly BusinessTimeRange.
 *
 * toString() renders the user-facing output format.
 * toIso() and toUnits(...) ignore presentation formatting where applicable.
 * For TIME_RANGE, toUnits(...) returns total selected range duration across the weekly segments.
 */
public final class TemporalValue {

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final ZonedDateTime EPOCH_UTC = Instant.EPOCH.atZone(ZoneOffset.UTC);
    private static final Pattern CALENDAR_PREFIX = Pattern.compile("(?i)^Calendar:(\\S+)\\s+(.+)$");

    public enum Kind { DATE_TIME, DURATION, TIME_RANGE }

    public enum Unit {
        YEARS, MONTHS, WEEKS, DAYS, HOURS, MINUTES, SECONDS, MILLIS, MICROS, NANOS;

        static Unit normalize(Unit unit) {
            return unit == null ? NANOS : unit;
        }
    }

    private final Kind kind;
    private final String originalInput;
    private final BusinessCalendar calendar;
    private final BusinessTime dateTime;
    private final Duration duration;
    private final BusinessTimeRange timeRange;
    private final String durationOutputFormat;

    private TemporalValue(
            Kind kind,
            String originalInput,
            BusinessCalendar calendar,
            BusinessTime dateTime,
            Duration duration,
            BusinessTimeRange timeRange,
            String durationOutputFormat
    ) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.originalInput = originalInput;
        this.calendar = calendar;
        this.dateTime = dateTime;
        this.duration = duration;
        this.timeRange = timeRange;
        this.durationOutputFormat = blankToNull(durationOutputFormat);
    }

    // ---------------- factories: explicit type paths ----------------

    public static TemporalValue dateTime(String spec) {
        Objects.requireNonNull(spec, "spec");
        String raw = stripPrefix(spec.trim(), "DateTime:");

        BusinessCalendar calendar = CalendarRegistry.getCalendar();
        Matcher m = CALENDAR_PREFIX.matcher(raw);
        if (m.matches()) {
            calendar = CalendarRegistry.get(m.group(1).trim());
            raw = m.group(2).trim();
        }

        return dateTime(spec, BusinessTime.evaluate(calendar, raw));
    }

    public static TemporalValue dateTime(BusinessCalendar calendar, String spec) {
        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(spec, "spec");
        String raw = stripPrefix(spec.trim(), "DateTime:");
        return dateTime(spec, BusinessTime.evaluate(calendar, raw));
    }

    public static TemporalValue dateTime(BusinessTime value) {
        return dateTime(null, value);
    }

    public static TemporalValue dateTime(String originalInput, BusinessTime value) {
        Objects.requireNonNull(value, "value");
        return new TemporalValue(Kind.DATE_TIME, originalInput, value.calendar(), value, null, null, null);
    }

    public static TemporalValue dateTime(ZonedDateTime value) {
        Objects.requireNonNull(value, "value");
        BusinessCalendar calendar = CalendarRegistry.getCalendar();
        return dateTime(new BusinessTime(calendar, value.withZoneSameInstant(calendar.zone())));
    }

    public static TemporalValue dateTime(Instant value) {
        Objects.requireNonNull(value, "value");
        return dateTime(value.atZone(ZoneOffset.UTC));
    }

    public static TemporalValue dateTime(OffsetDateTime value) {
        Objects.requireNonNull(value, "value");
        return dateTime(value.toInstant());
    }

    public static TemporalValue duration(String spec) {
        return DurationFormattingUtils.evaluate(spec);
    }

    public static TemporalValue duration(Duration value) {
        return duration(null, value, null);
    }

    public static TemporalValue duration(String originalInput, Duration value, String outputFormat) {
        Objects.requireNonNull(value, "value");
        return new TemporalValue(Kind.DURATION, originalInput, null, null, value, null, outputFormat);
    }

    public static TemporalValue timeRange(String spec) {
        Objects.requireNonNull(spec, "spec");
        String raw = stripPrefix(spec.trim(), "TimeRange:");
        return timeRange(spec, BusinessTimeRange.parse(raw));
    }

    public static TemporalValue timeRange(BusinessTimeRange value) {
        return timeRange(null, value);
    }

    public static TemporalValue timeRange(String originalInput, BusinessTimeRange value) {
        Objects.requireNonNull(value, "value");
        return new TemporalValue(Kind.TIME_RANGE, originalInput, null, null, null, value, null);
    }

    /**
     * Object convenience for already-resolved date/time objects. String is intentionally not accepted here.
     * Use dateTime(String) or duration(String) to avoid kind ambiguity.
     */
    public static TemporalValue ofDateTimeObject(Object value) {
        if (value instanceof TemporalValue tv) {
            if (!tv.isDateTime()) throw new IllegalArgumentException("TemporalValue is not DATE_TIME");
            return tv;
        }
        if (value instanceof BusinessTime bt) return dateTime(bt);
        if (value instanceof ZonedDateTime zdt) return dateTime(zdt);
        if (value instanceof OffsetDateTime odt) return dateTime(odt);
        if (value instanceof Instant instant) return dateTime(instant);
        throw new IllegalArgumentException("Unsupported date/time object: " + typeName(value));
    }

    /**
     * Object convenience for already-resolved durations. String is intentionally not accepted here.
     * Use duration(String) for duration expression strings.
     */
    public static TemporalValue ofDurationObject(Object value) {
        if (value instanceof TemporalValue tv) {
            if (!tv.isDuration()) throw new IllegalArgumentException("TemporalValue is not DURATION");
            return tv;
        }
        if (value instanceof Duration d) return duration(d);
        throw new IllegalArgumentException("Unsupported duration object: " + typeName(value));
    }

    /** Convenience for already-resolved time-range values. */
    public static TemporalValue ofTimeRangeObject(Object value) {
        if (value instanceof TemporalValue tv) {
            if (!tv.isTimeRange()) throw new IllegalArgumentException("TemporalValue is not TIME_RANGE");
            return tv;
        }
        if (value instanceof BusinessTimeRange tr) return timeRange(tr);
        throw new IllegalArgumentException("Unsupported time-range object: " + typeName(value));
    }

    // ---------------- accessors ----------------

    public Kind kind() { return kind; }
    public String originalInput() { return originalInput; }
    public Optional<BusinessCalendar> calendar() { return Optional.ofNullable(calendar); }
    public Optional<BusinessTime> businessTime() { return Optional.ofNullable(dateTime); }
    public Optional<Duration> duration() { return Optional.ofNullable(duration); }
    public Optional<BusinessTimeRange> timeRange() { return Optional.ofNullable(timeRange); }
    public Optional<String> durationOutputFormat() { return Optional.ofNullable(durationOutputFormat); }

    public boolean isDateTime() { return kind == Kind.DATE_TIME; }
    public boolean isDuration() { return kind == Kind.DURATION; }
    public boolean isTimeRange() { return kind == Kind.TIME_RANGE; }

    public BusinessTime requireBusinessTime() {
        if (!isDateTime()) throw new IllegalStateException("TemporalValue is not DATE_TIME");
        return dateTime;
    }

    public Duration requireDuration() {
        if (!isDuration()) throw new IllegalStateException("TemporalValue is not DURATION");
        return duration;
    }

    public BusinessTimeRange requireTimeRange() {
        if (!isTimeRange()) throw new IllegalStateException("TemporalValue is not TIME_RANGE");
        return timeRange;
    }

    // ---------------- normalization / comparison helpers ----------------

    /** DATE_TIME -> ISO instant. DURATION -> ISO-8601 Duration. TIME_RANGE -> canonical range string. */
    public String toIso() {
        return switch (kind) {
            case DATE_TIME -> dateTime.value().toInstant().toString();
            case DURATION -> duration.toString();
            case TIME_RANGE -> timeRange.toCanonicalString();
        };
    }

    /** Default comparison unit is nanoseconds. */
    public BigDecimal toUnits() {
        return toUnits(Unit.NANOS);
    }

    public BigDecimal toUnits(Unit unit) {
        unit = Unit.normalize(unit);
        return switch (kind) {
            case DATE_TIME -> dateTimeToUnits(dateTime.value().toInstant(), unit);
            case DURATION -> durationToUnits(duration, unit);
            case TIME_RANGE -> durationToUnits(timeRange.totalDuration(), unit);
        };
    }

    public BigInteger toNanos() {
        return switch (kind) {
            case DATE_TIME -> instantToEpochNanos(dateTime.value().toInstant());
            case DURATION -> durationToNanos(duration);
            case TIME_RANGE -> timeRange.totalNanos();
        };
    }

    public Instant toInstant() {
        return requireBusinessTime().value().toInstant();
    }

    // ---------------- display ----------------

    /** Renders the value using its presentation/output settings. */
    public String render() {
        return toString();
    }

    @Override
    public String toString() {
        return switch (kind) {
            case DATE_TIME -> dateTime.render();
            case DURATION -> DurationFormattingUtils.format(duration, durationOutputFormat);
            case TIME_RANGE -> timeRange.toCanonicalString();
        };
    }

    // ---------------- internals ----------------

    private static BigDecimal dateTimeToUnits(Instant instant, Unit unit) {
        if (unit == Unit.YEARS || unit == Unit.MONTHS) {
            ZonedDateTime z = instant.atZone(ZoneOffset.UTC);
            return BigDecimal.valueOf(unit == Unit.YEARS
                    ? ChronoUnit.YEARS.between(EPOCH_UTC, z)
                    : ChronoUnit.MONTHS.between(EPOCH_UTC, z));
        }
        return divide(instantToEpochNanos(instant), nanosPerUnit(unit));
    }

    private static BigDecimal durationToUnits(Duration duration, Unit unit) {
        if (unit == Unit.YEARS || unit == Unit.MONTHS) {
            throw new IllegalArgumentException("Duration cannot be safely converted to calendar-relative unit: " + unit);
        }
        return divide(durationToNanos(duration), nanosPerUnit(unit));
    }

    private static BigInteger instantToEpochNanos(Instant instant) {
        return BigInteger.valueOf(instant.getEpochSecond())
                .multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(instant.getNano()));
    }

    private static BigInteger durationToNanos(Duration duration) {
        return BigInteger.valueOf(duration.getSeconds())
                .multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(duration.getNano()));
    }

    private static BigInteger nanosPerUnit(Unit unit) {
        return switch (unit) {
            case WEEKS -> BigInteger.valueOf(7L * 24L * 3600L * 1_000_000_000L);
            case DAYS -> BigInteger.valueOf(24L * 3600L * 1_000_000_000L);
            case HOURS -> BigInteger.valueOf(3600L * 1_000_000_000L);
            case MINUTES -> BigInteger.valueOf(60L * 1_000_000_000L);
            case SECONDS -> BigInteger.valueOf(1_000_000_000L);
            case MILLIS -> BigInteger.valueOf(1_000_000L);
            case MICROS -> BigInteger.valueOf(1_000L);
            case NANOS -> BigInteger.ONE;
            case YEARS, MONTHS -> throw new IllegalArgumentException("No exact nanosecond size for unit: " + unit);
        };
    }

    private static BigDecimal divide(BigInteger numerator, BigInteger denominator) {
        return new BigDecimal(numerator)
                .divide(new BigDecimal(denominator), 18, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private static String stripPrefix(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length())
                ? text.substring(prefix.length()).trim()
                : text;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    public Duration toDuration() {
        return switch (kind) {
            case DURATION -> duration;
            case TIME_RANGE -> timeRange.totalDuration();
            case DATE_TIME -> throw new IllegalStateException("DATE_TIME does not have a duration");
        };
    }
}
