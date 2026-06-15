package tools.dscode.common.util.datetime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Formatting and evaluation support for standalone java.time.Duration values. */
public final class DurationFormattingUtils {

    private DurationFormattingUtils() {}

    public record ParsedDurationSpec(String originalInput, Duration duration, String outputFormat) {}

    /** Parses a duration expression and returns the resolved temporal wrapper. */
    public static TemporalValue evaluate(String spec) {
        ParsedDurationSpec parsed = parseSpec(spec);
        return TemporalValue.duration(parsed.originalInput(), parsed.duration(), parsed.outputFormat());
    }

    /** Alias for evaluate(...), useful when callers want value-like naming. */
    public static TemporalValue eval(String spec) {
        return evaluate(spec);
    }

    /** Parses a duration expression and ignores any output formatting clause. */
    public static Duration parseDuration(String spec) {
        return parseSpec(spec).duration();
    }

    /** Parses optional "Duration:" and optional trailing "format:" without rendering. */
    public static ParsedDurationSpec parseSpec(String spec) {
        Objects.requireNonNull(spec, "spec");
        String raw = stripPrefix(spec.trim(), "Duration:");
        if (raw.isEmpty()) throw new IllegalArgumentException("Duration spec is blank");

        String format = null;
        int fmtIdx = indexOfIgnoreCase(raw, "format:");
        if (fmtIdx >= 0) {
            format = blankToNull(raw.substring(fmtIdx + "format:".length()));
            raw = raw.substring(0, fmtIdx).trim();
        }

        return new ParsedDurationSpec(spec, DateTimeDeltaParsingUtils.parseDuration(raw), format);
    }

    /** Formats a duration. Null/blank format defaults to ISO-8601 Duration. */
    public static String format(Duration duration, String format) {
        Objects.requireNonNull(duration, "duration");
        String f = (format == null || format.isBlank()) ? "iso" : format.trim().toLowerCase(Locale.ROOT).replace('-', '_');

        return switch (f) {
            case "iso", "iso_8601", "iso8601" -> duration.toString();
            case "nanos", "nanoseconds", "ns" -> nanos(duration).toString();
            case "micros", "microseconds", "us" -> decimalUnit(duration, BigDecimal.valueOf(1_000));
            case "millis", "milliseconds", "ms" -> decimalUnit(duration, BigDecimal.valueOf(1_000_000));
            case "seconds", "second", "secs", "sec", "s" -> decimalUnit(duration, BigDecimal.valueOf(1_000_000_000L));
            case "minutes", "minute", "mins", "min", "m" -> decimalUnit(duration, BigDecimal.valueOf(60L * 1_000_000_000L));
            case "hours", "hour", "hrs", "hr", "h" -> decimalUnit(duration, BigDecimal.valueOf(3600L * 1_000_000_000L));
            case "days", "day", "d" -> decimalUnit(duration, BigDecimal.valueOf(86_400L * 1_000_000_000L));
            case "hh:mm", "h:mm" -> formatClock(duration, false);
            case "hh:mm:ss", "h:mm:ss" -> formatClock(duration, true);
            case "human" -> human(duration);
            default -> throw new IllegalArgumentException("Unsupported duration format: \"" + format + "\"");
        };
    }

    private static String decimalUnit(Duration duration, BigDecimal nanosPerUnit) {
        return new BigDecimal(nanos(duration))
                .divide(nanosPerUnit, 18, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static BigInteger nanos(Duration d) {
        return BigInteger.valueOf(d.getSeconds())
                .multiply(BigInteger.valueOf(1_000_000_000L))
                .add(BigInteger.valueOf(d.getNano()));
    }

    private static String formatClock(Duration d, boolean includeSeconds) {
        boolean negative = d.isNegative();
        Duration abs = d.abs();
        long hours = abs.toHours();
        int minutes = abs.toMinutesPart();
        int seconds = abs.toSecondsPart();
        String body = includeSeconds
                ? String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
        return negative ? "-" + body : body;
    }

    private static String human(Duration d) {
        if (d.isZero()) return "0 seconds";
        boolean negative = d.isNegative();
        Duration abs = d.abs();
        List<String> parts = new ArrayList<>();
        addPart(parts, abs.toDays(), "day");
        addPart(parts, abs.toHoursPart(), "hour");
        addPart(parts, abs.toMinutesPart(), "minute");
        addPart(parts, abs.toSecondsPart(), "second");
        addPart(parts, abs.toMillisPart(), "millisecond");

        int remainingNanos = abs.toNanosPart() % 1_000_000;
        addPart(parts, remainingNanos / 1_000, "microsecond");
        addPart(parts, remainingNanos % 1_000, "nanosecond");

        String out = String.join(" ", parts);
        return negative ? "-" + out : out;
    }

    private static void addPart(List<String> parts, long value, String unit) {
        if (value != 0) parts.add(value + " " + unit + (value == 1 ? "" : "s"));
    }

    private static int indexOfIgnoreCase(String s, String needle) {
        return s.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    private static String stripPrefix(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length()) ? text.substring(prefix.length()).trim() : text;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
