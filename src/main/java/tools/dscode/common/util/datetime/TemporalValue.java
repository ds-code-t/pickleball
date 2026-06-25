package tools.dscode.common.util.datetime;

import tools.dscode.common.assertions.ValueWrapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolved temporal value that preserves both the original input and the parsed object.
 *
 * DATE_TIME values wrap BusinessTime/ZonedDateTime.
 * DELTA values wrap ordered calendar/time deltas.
 * TIME_RANGE values wrap a parsed weekly BusinessTimeRange.
 * TEXT/BOOLEAN/NULL values are scalar results from date/time pipe modifiers.
 */
public final class TemporalValue {

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final ZonedDateTime EPOCH_UTC = Instant.EPOCH.atZone(ZoneOffset.UTC);
    private static final Pattern CALENDAR_PREFIX = Pattern.compile("(?i)^Calendar:(\\S+)\\s+(.+)$");

    public enum Kind { DATE_TIME, DELTA, TIME_RANGE, TEXT, BOOLEAN, NULL }

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
    private final TemporalDelta delta;
    private final BusinessTimeRange timeRange;
    private final String text;
    private final Boolean bool;
    private final String deltaOutputFormat;

    private TemporalValue(
            Kind kind,
            String originalInput,
            BusinessCalendar calendar,
            BusinessTime dateTime,
            TemporalDelta delta,
            BusinessTimeRange timeRange,
            String text,
            Boolean bool,
            String deltaOutputFormat
    ) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.originalInput = originalInput;
        this.calendar = calendar;
        this.dateTime = dateTime;
        this.delta = delta;
        this.timeRange = timeRange;
        this.text = text;
        this.bool = bool;
        this.deltaOutputFormat = blankToNull(deltaOutputFormat);
    }

    public static TemporalValue dateTime(String spec) {
        Objects.requireNonNull(spec, "spec");
        String raw = stripPrefix(spec.trim(), "DateTime:");

        BusinessCalendar calendar = CalendarRegistry.getCalendar();
        Matcher m = CALENDAR_PREFIX.matcher(raw);
        if (m.matches()) {
            calendar = CalendarRegistry.get(m.group(1).trim());
            raw = m.group(2).trim();
        }

        return fromDateTimeEvaluation(spec, BusinessTime.evaluateAny(calendar, raw));
    }

    public static TemporalValue dateTime(BusinessCalendar calendar, String spec) {
        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(spec, "spec");
        String raw = stripPrefix(spec.trim(), "DateTime:");
        return fromDateTimeEvaluation(spec, BusinessTime.evaluateAny(calendar, raw));
    }

    public static TemporalValue dateTime(BusinessTime value) {
        return dateTime(null, value);
    }

    public static TemporalValue dateTime(String originalInput, BusinessTime value) {
        Objects.requireNonNull(value, "value");
        return new TemporalValue(Kind.DATE_TIME, originalInput, value.calendar(), value, null, null, null, null, null);
    }

    public static TemporalValue fromDateTimeEvaluation(String originalInput, Object value) {
        if (value == null) return nullValue(originalInput);
        if (value instanceof BusinessTime bt) return dateTime(originalInput, bt);
        if (value instanceof Boolean b) return bool(originalInput, b);
        if (value instanceof String s) return text(originalInput, s);
        throw new IllegalArgumentException("Unsupported date/time evaluation result: " + typeName(value));
    }

    public static TemporalValue text(String originalInput, String value) {
        Objects.requireNonNull(value, "value");
        return new TemporalValue(Kind.TEXT, originalInput, null, null, null, null, value, null, null);
    }

    public static TemporalValue bool(String originalInput, Boolean value) {
        Objects.requireNonNull(value, "value");
        return new TemporalValue(Kind.BOOLEAN, originalInput, null, null, null, null, null, value, null);
    }

    public static TemporalValue nullValue(String originalInput) {
        return new TemporalValue(Kind.NULL, originalInput, null, null, null, null, null, null, null);
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

    public static TemporalValue delta(String spec) {
        return DurationFormattingUtils.evaluate(spec);
    }

    public static TemporalValue delta(TemporalDelta value) {
        return delta(null, value, null);
    }

    public static TemporalValue delta(String originalInput, TemporalDelta value, String outputFormat) {
        Objects.requireNonNull(value, "value");
        return new TemporalValue(Kind.DELTA, originalInput, null, null, value, null, null, null, outputFormat);
    }

    public static TemporalValue duration(String spec) {
        return delta(spec);
    }

    public static TemporalValue duration(Duration value) {
        return duration(null, value, null);
    }

    public static TemporalValue duration(String originalInput, Duration value, String outputFormat) {
        Objects.requireNonNull(value, "value");
        return delta(originalInput, TemporalDelta.fromDuration(value), outputFormat);
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
        return new TemporalValue(Kind.TIME_RANGE, originalInput, null, null, null, value, null, null, null);
    }

    public static TemporalValue fromValueWrapper(ValueWrapper valueWrapper) {
        Objects.requireNonNull(valueWrapper, "valueWrapper");
        BusinessCalendar calendar = CalendarRegistry.getCalendar();

        return switch (valueWrapper.type.name()) {
            case "TIME_INSTANCE", "DATE_TIME" -> fromDateTimeValueWrapper(valueWrapper, calendar);
            case "TIME_DURATION", "DURATION", "DELTA" -> fromDeltaValueWrapper(valueWrapper);
            case "TIME_RANGE" -> fromTimeRangeValueWrapper(valueWrapper);
            default -> throw new IllegalArgumentException(
                    "Unsupported temporal ValueWrapper type: " + valueWrapper.type
                            + ". Expected one of TIME_INSTANCE, TIME_RANGE, TIME_DURATION."
            );
        };
    }

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

    public static TemporalValue ofDeltaObject(Object value) {
        if (value instanceof TemporalValue tv) {
            if (!tv.isDelta()) throw new IllegalArgumentException("TemporalValue is not DELTA");
            return tv;
        }
        if (value instanceof TemporalDelta td) return delta(td);
        if (value instanceof Duration d) return duration(d);
        throw new IllegalArgumentException("Unsupported delta object: " + typeName(value));
    }

    public static TemporalValue ofDurationObject(Object value) {
        return ofDeltaObject(value);
    }

    public static TemporalValue ofTimeRangeObject(Object value) {
        if (value instanceof TemporalValue tv) {
            if (!tv.isTimeRange()) throw new IllegalArgumentException("TemporalValue is not TIME_RANGE");
            return tv;
        }
        if (value instanceof BusinessTimeRange tr) return timeRange(tr);
        throw new IllegalArgumentException("Unsupported time-range object: " + typeName(value));
    }

    public Kind kind() { return kind; }
    public String originalInput() { return originalInput; }
    public Optional<BusinessCalendar> calendar() { return Optional.ofNullable(calendar); }
    public Optional<BusinessTime> businessTime() { return Optional.ofNullable(dateTime); }
    public Optional<TemporalDelta> delta() { return Optional.ofNullable(delta); }
    public Optional<Duration> duration() { return Optional.ofNullable(delta).map(TemporalDelta::toDuration); }
    public Optional<BusinessTimeRange> timeRange() { return Optional.ofNullable(timeRange); }
    public Optional<String> text() { return Optional.ofNullable(text); }
    public Optional<Boolean> bool() { return Optional.ofNullable(bool); }
    public Optional<String> deltaOutputFormat() { return Optional.ofNullable(deltaOutputFormat); }
    public Optional<String> durationOutputFormat() { return deltaOutputFormat(); }

    public boolean isDateTime() { return kind == Kind.DATE_TIME; }
    public boolean isDelta() { return kind == Kind.DELTA; }
    public boolean isDuration() { return isDelta(); }
    public boolean isTimeRange() { return kind == Kind.TIME_RANGE; }
    public boolean isText() { return kind == Kind.TEXT; }
    public boolean isBoolean() { return kind == Kind.BOOLEAN; }
    public boolean isNull() { return kind == Kind.NULL; }

    public BusinessTime requireBusinessTime() {
        if (!isDateTime()) throw new IllegalStateException("TemporalValue is not DATE_TIME");
        return dateTime;
    }

    public TemporalDelta requireDelta() {
        if (!isDelta()) throw new IllegalStateException("TemporalValue is not DELTA");
        return delta;
    }

    public Duration requireDuration() {
        return requireDelta().toDuration();
    }

    public BusinessTimeRange requireTimeRange() {
        if (!isTimeRange()) throw new IllegalStateException("TemporalValue is not TIME_RANGE");
        return timeRange;
    }

    public String requireText() {
        if (!isText()) throw new IllegalStateException("TemporalValue is not TEXT");
        return text;
    }

    public Boolean requireBoolean() {
        if (!isBoolean()) throw new IllegalStateException("TemporalValue is not BOOLEAN");
        return bool;
    }

    public Object toObject() {
        return switch (kind) {
            case DATE_TIME -> dateTime;
            case DELTA -> delta;
            case TIME_RANGE -> timeRange;
            case TEXT -> text;
            case BOOLEAN -> bool;
            case NULL -> null;
        };
    }

    public String toIso() {
        return switch (kind) {
            case DATE_TIME -> dateTime.value().toInstant().toString();
            case DELTA -> delta.toIsoString();
            case TIME_RANGE -> timeRange.toCanonicalString();
            case TEXT, BOOLEAN, NULL -> throw new IllegalStateException(kind + " does not have an ISO representation");
        };
    }

    public BigDecimal toUnits() {
        return toUnits(Unit.NANOS);
    }

    public BigDecimal toUnits(Unit unit) {
        unit = Unit.normalize(unit);
        return switch (kind) {
            case DATE_TIME -> dateTimeToUnits(dateTime.value().toInstant(), unit);
            case DELTA -> deltaToUnits(delta, unit);
            case TIME_RANGE -> durationToUnits(timeRange.totalDuration(), unit);
            case TEXT, BOOLEAN, NULL -> throw new IllegalStateException(kind + " cannot be converted to units");
        };
    }

    public BigInteger toNanos() {
        return switch (kind) {
            case DATE_TIME -> instantToEpochNanos(dateTime.value().toInstant());
            case DELTA -> durationToNanos(delta.toDuration());
            case TIME_RANGE -> timeRange.totalNanos();
            case TEXT, BOOLEAN, NULL -> throw new IllegalStateException(kind + " cannot be converted to nanoseconds");
        };
    }

    public Instant toInstant() {
        return requireBusinessTime().value().toInstant();
    }

    public String render() {
        return toString();
    }

    @Override
    public String toString() {
        return switch (kind) {
            case DATE_TIME -> dateTime.render();
            case DELTA -> DurationFormattingUtils.format(delta, deltaOutputFormat);
            case TIME_RANGE -> timeRange.toCanonicalString();
            case TEXT -> text;
            case BOOLEAN -> bool.toString();
            case NULL -> "null";
        };
    }

    private static BigDecimal dateTimeToUnits(Instant instant, Unit unit) {
        if (unit == Unit.YEARS || unit == Unit.MONTHS) {
            ZonedDateTime z = instant.atZone(ZoneOffset.UTC);
            return BigDecimal.valueOf(unit == Unit.YEARS
                    ? ChronoUnit.YEARS.between(EPOCH_UTC, z)
                    : ChronoUnit.MONTHS.between(EPOCH_UTC, z));
        }
        return divide(instantToEpochNanos(instant), nanosPerUnit(unit));
    }

    private static BigDecimal deltaToUnits(TemporalDelta delta, Unit unit) {
        ZonedDateTime start = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime end = delta.applyTo(start);
        if (unit == Unit.YEARS) return BigDecimal.valueOf(ChronoUnit.YEARS.between(start, end));
        if (unit == Unit.MONTHS) return BigDecimal.valueOf(ChronoUnit.MONTHS.between(start, end));
        return durationToUnits(Duration.between(start, end), unit);
    }

    private static TemporalValue fromDateTimeValueWrapper(ValueWrapper valueWrapper, BusinessCalendar calendar) {
        Object value = valueWrapper.getValue();
        String originalInput = valueWrapper.toString();

        if (value instanceof TemporalValue tv) {
            if (!tv.isDateTime()) throw new IllegalArgumentException("ValueWrapper DATE_TIME value is not DATE_TIME");
            return tv;
        }
        if (value instanceof BusinessTime bt) return dateTime(originalInput, bt);
        if (value instanceof ZonedDateTime zdt) {
            return dateTime(originalInput, new BusinessTime(calendar, zdt.withZoneSameInstant(calendar.zone())));
        }
        if (value instanceof OffsetDateTime odt) {
            return dateTime(originalInput, new BusinessTime(calendar, odt.toInstant().atZone(calendar.zone())));
        }
        if (value instanceof Instant instant) {
            return dateTime(originalInput, new BusinessTime(calendar, instant.atZone(calendar.zone())));
        }
        if (value instanceof String s) {
            return dateTime(originalInput, BusinessTime.evaluate(calendar, s));
        }
        if (value != null) {
            return dateTime(originalInput, BusinessTime.evaluate(calendar, value.toString()));
        }

        throw new IllegalArgumentException("ValueWrapper DATE_TIME value is null");
    }

    private static TemporalValue fromDeltaValueWrapper(ValueWrapper valueWrapper) {
        Object value = valueWrapper.getValue();
        String originalInput = valueWrapper.toString();

        if (value instanceof TemporalValue tv) {
            if (!tv.isDelta()) throw new IllegalArgumentException("ValueWrapper DELTA value is not DELTA");
            return tv;
        }
        if (value instanceof TemporalDelta td) return delta(originalInput, td, null);
        if (value instanceof Duration d) return duration(originalInput, d, null);
        if (value instanceof String s) return delta(s);
        if (value != null) return delta(value.toString());

        throw new IllegalArgumentException("ValueWrapper DELTA value is null");
    }

    private static TemporalValue fromTimeRangeValueWrapper(ValueWrapper valueWrapper) {
        Object value = valueWrapper.getValue();
        String originalInput = valueWrapper.toString();

        if (value instanceof TemporalValue tv) {
            if (!tv.isTimeRange()) throw new IllegalArgumentException("ValueWrapper TIME_RANGE value is not TIME_RANGE");
            return tv;
        }
        if (value instanceof BusinessTimeRange tr) return timeRange(originalInput, tr);
        if (value instanceof String s) return timeRange(s);
        if (value != null) return timeRange(value.toString());

        throw new IllegalArgumentException("ValueWrapper TIME_RANGE value is null");
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
            case DELTA -> delta.toDuration();
            case TIME_RANGE -> timeRange.totalDuration();
            case DATE_TIME, TEXT, BOOLEAN, NULL -> throw new IllegalStateException(kind + " does not have a duration");
        };
    }
}
