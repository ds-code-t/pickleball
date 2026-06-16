package tools.dscode.common.util.datetime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

/**
 * Convenience normalization and conversion helpers for the date/time utilities.
 *
 * Design goals:
 *  - Date/time instances normalize through the underlying instant, not their rendered string.
 *  - Deltas normalize through a Duration conversion, not their rendered string.
 *  - Existing presentation clauses such as "format: ..." are ignored for normalization.
 *  - Inline date/time input clauses such as "pattern: ..." affect parsing, not normalization.
 *  - Existing date/time output-zone clauses such as "to America/Phoenix TimeZone" are ignored
 *    for instant normalization, because they are presentation-only in BusinessTime.eval(...).
 *
 * Recommended comparison values:
 *  - Date/time instance: dateTimeToEpochNanos(...) or dateTimeToInstant(...)
 *  - Delta/duration: durationToNanos(...) or parseDuration(...)
 *
 * Notes about years/months:
 *  - Delta years/months are converted using the current time as the anchor.
 *  - Date/time year/month conversion here means completed calendar years/months since
 *    1970-01-01T00:00:00Z in UTC. That is useful for coarse bucketing, but it is lossy.
 *    For exact ordering/equality comparisons, use nanos/millis/seconds instead.
 */
public final class TemporalConversionUtils {

    private static final BigInteger BI_NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigDecimal BD_NANOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);
    private static final ZonedDateTime UNIX_EPOCH_UTC = Instant.EPOCH.atZone(ZoneOffset.UTC);

    private TemporalConversionUtils() {}

    public enum TemporalKind {
        DATE_TIME,
        DELTA
    }

    // ---------------------------------------------------------------------
    // Explicit date/time instance normalization
    // ---------------------------------------------------------------------

    /**
     * Parses/evaluates any legal DateTime expression and returns the underlying instant.
     *
     * This intentionally ignores a trailing "format: ..." clause and presentation-only
     * "to <zone> TimeZone" clauses so that equivalent date/time specs normalize equally.
     */
    public static Instant dateTimeToInstant(BusinessCalendar calendar, String dateTimeSpec) {
        return dateTimeToZonedDateTime(calendar, dateTimeSpec).toInstant();
    }

    /**
     * Parses/evaluates any legal DateTime expression and returns the resulting ZonedDateTime.
     * The zone is the calendar zone after normal BusinessTime construction rules.
     */
    public static ZonedDateTime dateTimeToZonedDateTime(BusinessCalendar calendar, String dateTimeSpec) {
        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(dateTimeSpec, "dateTimeSpec");

        return BusinessTime.evaluateValue(calendar, dateTimeSpec);
    }

    /** Returns the canonical ISO instant string, e.g. 2026-02-02T10:00:00Z. */
    public static String dateTimeToIsoInstant(BusinessCalendar calendar, String dateTimeSpec) {
        return DateTimeFormatter.ISO_INSTANT.format(dateTimeToInstant(calendar, dateTimeSpec));
    }

    /** Returns an ISO zoned date/time string in the evaluated calendar zone. */
    public static String dateTimeToIsoZonedDateTime(BusinessCalendar calendar, String dateTimeSpec) {
        return DateTimeFormatter.ISO_ZONED_DATE_TIME.format(dateTimeToZonedDateTime(calendar, dateTimeSpec));
    }

    /** Returns nanoseconds since the Unix epoch for exact date/time comparisons. */
    public static BigInteger dateTimeToEpochNanos(BusinessCalendar calendar, String dateTimeSpec) {
        return instantToEpochNanos(dateTimeToInstant(calendar, dateTimeSpec));
    }

    /** Returns milliseconds since the Unix epoch. Throws if the value does not fit in long. */
    public static long dateTimeToEpochMillis(BusinessCalendar calendar, String dateTimeSpec) {
        return dateTimeToInstant(calendar, dateTimeSpec).toEpochMilli();
    }

    /**
     * Converts a date/time instance to a numeric amount since the Unix epoch in the requested unit.
     *
     * Exact units: weeks, days, hours, minutes, seconds, millis, micros, nanos.
     * Calendar units: years, months are completed UTC calendar units since 1970-01-01T00:00:00Z.
     */
    public static BigDecimal dateTimeToUnits(BusinessCalendar calendar, String dateTimeSpec, String unit) {
        Unit u = Unit.from(unit);
        Instant instant = dateTimeToInstant(calendar, dateTimeSpec);

        if (u == Unit.YEARS || u == Unit.MONTHS) {
            ZonedDateTime z = instant.atZone(ZoneOffset.UTC);
            long completed = (u == Unit.YEARS)
                    ? ChronoUnit.YEARS.between(UNIX_EPOCH_UTC, z)
                    : ChronoUnit.MONTHS.between(UNIX_EPOCH_UTC, z);
            return BigDecimal.valueOf(completed);
        }

        return divideNanos(instantToEpochNanos(instant), u.nanosPerUnitExact());
    }

    // ---------------------------------------------------------------------
    // Explicit duration normalization
    // ---------------------------------------------------------------------

    /**
     * Parses any legal Duration expression and returns java.time.Duration.
     * A trailing "format: ..." clause is ignored for normalization.
     */
    public static Duration parseDuration(String durationSpec) {
        Objects.requireNonNull(durationSpec, "durationSpec");

        String raw = stripCaseInsensitivePrefix(durationSpec.trim(), "Duration:").trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Duration spec is blank");

        raw = stripFormatClause(raw).trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Duration expression is blank");

        return DurationFormattingUtils.parseDuration(raw);
    }

    /** Returns the canonical Java/ISO-8601 Duration string, e.g. PT1H30M. */
    public static String durationToIso(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        return duration.toString();
    }

    /** Returns the canonical Java/ISO-8601 Duration string, e.g. PT1H30M. */
    public static String durationToIso(String durationSpec) {
        return durationToIso(parseDuration(durationSpec));
    }

    /** Returns total duration nanoseconds for exact duration comparisons. */
    public static BigInteger durationToNanos(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        return durationToTotalNanos(duration);
    }

    /** Returns total duration nanoseconds for exact duration comparisons. */
    public static BigInteger durationToNanos(String durationSpec) {
        return durationToNanos(parseDuration(durationSpec));
    }

    /**
     * Converts a duration to a numeric amount in the requested exact unit.
     *
     * Supported exact units: weeks, days, hours, minutes, seconds, millis, micros, nanos.
     * Years/months intentionally throw because a Duration is exact elapsed time and those units
     * are calendar-relative.
     */
    public static BigDecimal durationToUnits(String durationSpec, String unit) {
        return durationToUnits(parseDuration(durationSpec), unit);
    }

    /**
     * Converts a duration to a numeric amount in the requested exact unit.
     *
     * Supported exact units: weeks, days, hours, minutes, seconds, millis, micros, nanos.
     * Years/months intentionally throw because a Duration is exact elapsed time and those units
     * are calendar-relative.
     */
    public static BigDecimal durationToUnits(Duration duration, String unit) {
        Objects.requireNonNull(duration, "duration");
        Unit u = Unit.from(unit);
        if (u == Unit.YEARS || u == Unit.MONTHS) {
            throw new IllegalArgumentException("Duration cannot be safely converted to calendar-relative unit: " + unit);
        }
        return divideNanos(durationToTotalNanos(duration), u.nanosPerUnitExact());
    }

    // ---------------------------------------------------------------------
    // Typed convenience dispatchers
    // ---------------------------------------------------------------------

    /**
     * Typed ISO dispatcher. Prefer explicit dateTimeToIsoInstant(...) or durationToIso(...)
     * when the caller already knows the temporal kind.
     */
    public static String toIso(TemporalKind kind, BusinessCalendar calendar, String spec) {
        Objects.requireNonNull(kind, "kind");
        return switch (kind) {
            case DATE_TIME -> dateTimeToIsoInstant(calendar, spec);
            case DELTA -> durationToIso(spec);
        };
    }

    /**
     * Typed unit dispatcher. Prefer explicit dateTimeToUnits(...) or durationToUnits(...)
     * when the caller already knows the temporal kind.
     */
    public static BigDecimal toUnits(TemporalKind kind, BusinessCalendar calendar, String spec, String unit) {
        Objects.requireNonNull(kind, "kind");
        return switch (kind) {
            case DATE_TIME -> dateTimeToUnits(calendar, spec, unit);
            case DELTA -> durationToUnits(spec, unit);
        };
    }

    /**
     * Best-effort ISO dispatcher for callers that truly do not know the type.
     *
     * Heuristics:
     *  - "Duration:" prefix => duration
     *  - "DateTime:" prefix => date/time
     *  - ISO duration-looking strings starting with P/PT => duration
     *  - friendly strings made only of duration tokens => duration
     *  - otherwise => date/time
     *
     * Prefer the typed methods for assertion code where possible.
     */
    public static String toIsoAuto(BusinessCalendar calendar, String spec) {
        return isProbablyDuration(spec) ? durationToIso(spec) : dateTimeToIsoInstant(calendar, spec);
    }

    /** Best-effort unit dispatcher. Prefer the typed methods for assertion code where possible. */
    public static BigDecimal toUnitsAuto(BusinessCalendar calendar, String spec, String unit) {
        return isProbablyDuration(spec) ? durationToUnits(spec, unit) : dateTimeToUnits(calendar, spec, unit);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private enum Unit {
        YEARS,
        MONTHS,
        WEEKS,
        DAYS,
        HOURS,
        MINUTES,
        SECONDS,
        MILLIS,
        MICROS,
        NANOS;

        static Unit from(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("Unit must not be null or blank");
            }

            String u = raw.trim().toLowerCase(Locale.ROOT);

            return switch (u) {
                case "y", "yr", "yrs", "year", "years" -> YEARS;
                case "mo", "mon", "mons", "month", "months" -> MONTHS;
                case "w", "wk", "wks", "week", "weeks" -> WEEKS;
                case "d", "day", "days" -> DAYS;
                case "h", "hr", "hrs", "hour", "hours" -> HOURS;
                case "m", "min", "mins", "minute", "minutes" -> MINUTES;
                case "s", "sec", "secs", "second", "seconds" -> SECONDS;
                case "ms", "milli", "millis", "millisecond", "milliseconds" -> MILLIS;
                case "us", "µs", "μs", "micro", "micros", "microsecond", "microseconds" -> MICROS;
                case "ns", "nano", "nanos", "nanosecond", "nanoseconds", "nanno", "nannos" -> NANOS;
                default -> throw new IllegalArgumentException("Unsupported temporal unit: " + raw);
            };
        }

        BigInteger nanosPerUnitExact() {
            return switch (this) {
                case WEEKS -> BigInteger.valueOf(7L * 24L * 3600L).multiply(BI_NANOS_PER_SECOND);
                case DAYS -> BigInteger.valueOf(24L * 3600L).multiply(BI_NANOS_PER_SECOND);
                case HOURS -> BigInteger.valueOf(3600L).multiply(BI_NANOS_PER_SECOND);
                case MINUTES -> BigInteger.valueOf(60L).multiply(BI_NANOS_PER_SECOND);
                case SECONDS -> BI_NANOS_PER_SECOND;
                case MILLIS -> BigInteger.valueOf(1_000_000L);
                case MICROS -> BigInteger.valueOf(1_000L);
                case NANOS -> BigInteger.ONE;
                case YEARS, MONTHS -> throw new IllegalArgumentException(this + " does not have an exact nanosecond length");
            };
        }
    }

    private static BigInteger instantToEpochNanos(Instant instant) {
        return BigInteger.valueOf(instant.getEpochSecond())
                .multiply(BI_NANOS_PER_SECOND)
                .add(BigInteger.valueOf(instant.getNano()));
    }

    private static BigInteger durationToTotalNanos(Duration duration) {
        return BigInteger.valueOf(duration.getSeconds())
                .multiply(BI_NANOS_PER_SECOND)
                .add(BigInteger.valueOf(duration.getNano()));
    }

    private static BigDecimal divideNanos(BigInteger nanos, BigInteger nanosPerUnit) {
        BigDecimal numerator = new BigDecimal(nanos);
        BigDecimal denominator = new BigDecimal(nanosPerUnit);
        BigDecimal out = numerator.divide(denominator, 18, RoundingMode.HALF_UP);
        return out.stripTrailingZeros();
    }

    private static String stripFormatClause(String raw) {
        int idx = indexOfIgnoreCase(raw, "format:");
        return idx < 0 ? raw : raw.substring(0, idx);
    }

    private static String stripCaseInsensitivePrefix(String raw, String prefix) {
        if (raw.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return raw.substring(prefix.length());
        }
        return raw;
    }

    private static int indexOfIgnoreCase(String s, String needle) {
        return s.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean isProbablyDuration(String spec) {
        if (spec == null) return false;
        String s = spec.trim();
        if (s.isEmpty()) return false;

        if (s.regionMatches(true, 0, "Duration:", 0, "Duration:".length())) return true;
        if (s.regionMatches(true, 0, "Delta:", 0, "Delta:".length())) return true;
        if (s.regionMatches(true, 0, "DateTime:", 0, "DateTime:".length())) return false;

        String noFormat = stripFormatClause(s).trim();
        String t = noFormat;
        if (t.startsWith("+") || t.startsWith("-")) t = t.substring(1).trim();
        if (t.length() >= 2 && (t.charAt(0) == 'P' || t.charAt(0) == 'p')) return true;

        // Friendly duration heuristic: if the duration parser accepts it, treat it as a duration.
        // This makes "2 days" a duration, while strings like "today + 2 days" remain date/time.
        try {
            DurationFormattingUtils.parseSpec(noFormat);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
