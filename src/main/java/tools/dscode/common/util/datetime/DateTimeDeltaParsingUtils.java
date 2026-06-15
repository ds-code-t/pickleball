package tools.dscode.common.util.datetime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared parser for date/time deltas and exact Duration expressions.
 *
 * Date/time instance arithmetic intentionally allows:
 *  - whole calendar units: years/months/weeks/days
 *  - decimal exact units: weeks/days/hours/minutes/seconds/millis/micros/nanos
 *  - fractional years/months are rejected because they are calendar-relative.
 *
 * Standalone Duration arithmetic allows exact elapsed units only and rejects years/months.
 */
public final class DateTimeDeltaParsingUtils {

    private static final Pattern DECIMAL_TOKEN = Pattern.compile("([+-])?\\s*((?:\\d+(?:\\.\\d*)?)|(?:\\.\\d+))\\s*([\\p{L}]+)");
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

    private DateTimeDeltaParsingUtils() {}

    /** Parsed delta token. amount may be decimal for exact units. */
    public record Delta(BigDecimal amount, ChronoUnit unit) {
        public boolean isWholeNumber() {
            return amount.stripTrailingZeros().scale() <= 0;
        }

        public long wholeAmountExact() {
            return amount.longValueExact();
        }
    }

    /** Alias kept for code/readability on the duration side. */
    public record DecimalDelta(BigDecimal amount, ChronoUnit unit) {}

    /** Parses date/time instance delta syntax. Decimal exact-unit amounts are allowed. */
    public static List<Delta> parse(String expr) {
        String s = normalizeExpression(expr, false);
        if (s.isEmpty()) return List.of();
        return parseDeltas(s, expr);
    }

    /** Applies parsed date/time deltas sequentially to a real ZonedDateTime instance. */
    public static ZonedDateTime applyTo(ZonedDateTime start, String expr) {
        Objects.requireNonNull(start, "start");
        ZonedDateTime cur = start;

        for (Delta delta : parse(expr)) {
            if (delta.unit() == ChronoUnit.YEARS || delta.unit() == ChronoUnit.MONTHS) {
                if (!delta.isWholeNumber()) {
                    throw new IllegalArgumentException("Date/time expressions cannot include fractional months/years: \"" + expr + "\"");
                }
                cur = cur.plus(delta.wholeAmountExact(), delta.unit());
                continue;
            }

            // Preserve Java's calendar-based behavior for whole weeks/days.
            // Example: +2 days is a date-based operation on ZonedDateTime.
            if ((delta.unit() == ChronoUnit.WEEKS || delta.unit() == ChronoUnit.DAYS) && delta.isWholeNumber()) {
                cur = cur.plus(delta.wholeAmountExact(), delta.unit());
                continue;
            }

            // Decimal exact units are converted to an exact Duration.
            cur = cur.plus(toExactDuration(delta, expr));
        }

        return cur;
    }

    public static Duration parseDuration(String text) {
        Objects.requireNonNull(text, "text");
        String s = normalizeExpression(text, true);
        if (s.isEmpty()) throw new IllegalArgumentException("Duration expression is blank");

        if (looksLikeIsoDuration(s)) {
            try {
                return Duration.parse(s.toUpperCase(Locale.ROOT));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid ISO-8601 duration: \"" + text + "\"", e);
            }
        }

        return toDuration(s);
    }

    public static Duration toDuration(String expr) {
        Objects.requireNonNull(expr, "expr");
        String s = normalizeExpression(expr, true);
        if (s.isEmpty()) throw new IllegalArgumentException("Duration expression is blank");

        BigInteger nanos = BigInteger.ZERO;
        for (Delta d : parseDeltas(s, expr)) {
            if (d.unit() == ChronoUnit.YEARS || d.unit() == ChronoUnit.MONTHS) {
                throw new IllegalArgumentException("Duration expressions cannot include months/years: \"" + expr + "\"");
            }
            nanos = nanos.add(toNanos(d, expr));
        }

        BigInteger[] secondsAndNanos = nanos.divideAndRemainder(NANOS_PER_SECOND);
        return Duration.ofSeconds(secondsAndNanos[0].longValueExact(), secondsAndNanos[1].longValueExact());
    }

    private static Duration toExactDuration(Delta delta, String original) {
        BigInteger nanos = toNanos(delta, original);
        BigInteger[] secondsAndNanos = nanos.divideAndRemainder(NANOS_PER_SECOND);
        return Duration.ofSeconds(secondsAndNanos[0].longValueExact(), secondsAndNanos[1].longValueExact());
    }

    private static List<Delta> parseDeltas(String expr, String original) {
        String parsedText = withLeadingSign(expr);
        List<Delta> out = new ArrayList<>();
        Matcher m = DECIMAL_TOKEN.matcher(parsedText);
        int lastEnd = 0;
        char carrySign = '+';

        while (m.find()) {
            requireNoGap(m, lastEnd, parsedText, original);
            lastEnd = m.end();

            if (m.group(1) != null && !m.group(1).isBlank()) {
                carrySign = m.group(1).charAt(0);
            }

            BigDecimal amount = new BigDecimal(m.group(2));
            if (carrySign == '-') amount = amount.negate();
            out.add(new Delta(amount, normalizeUnit(m.group(3))));
        }

        requireNoTail(lastEnd, parsedText, original);
        if (out.isEmpty()) throw badChunk(original, original);
        return List.copyOf(out);
    }

    private static BigInteger toNanos(Delta delta, String original) {
        BigDecimal nanos = delta.amount().multiply(new BigDecimal(nanosPerUnit(delta.unit())));
        try {
            return nanos.setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Duration expression has sub-nanosecond precision: \"" + original + "\"", e);
        }
    }

    private static String normalizeExpression(String text, boolean durationMode) {
        String s = text == null ? "" : text.trim();
        if (durationMode) {
            s = stripPrefix(s, "Duration:")
                    .replaceAll("(?i)\\band\\b", " ")
                    .replace(',', ' ')
                    .trim();
        }
        return s;
    }

    private static String withLeadingSign(String s) {
        return s.isEmpty() || s.charAt(0) == '+' || s.charAt(0) == '-' ? s : "+ " + s;
    }

    private static boolean looksLikeIsoDuration(String s) {
        String t = s.startsWith("+") || s.startsWith("-") ? s.substring(1).trim() : s;
        return t.length() >= 2 && (t.charAt(0) == 'P' || t.charAt(0) == 'p');
    }

    private static BigInteger nanosPerUnit(ChronoUnit unit) {
        return switch (unit) {
            case WEEKS -> BigInteger.valueOf(7L * 24L * 3600L * 1_000_000_000L);
            case DAYS -> BigInteger.valueOf(24L * 3600L * 1_000_000_000L);
            case HOURS -> BigInteger.valueOf(3600L * 1_000_000_000L);
            case MINUTES -> BigInteger.valueOf(60L * 1_000_000_000L);
            case SECONDS -> BigInteger.valueOf(1_000_000_000L);
            case MILLIS -> BigInteger.valueOf(1_000_000L);
            case MICROS -> BigInteger.valueOf(1_000L);
            case NANOS -> BigInteger.ONE;
            default -> throw new IllegalArgumentException("Unsupported exact unit: " + unit);
        };
    }

    private static ChronoUnit normalizeUnit(String raw) {
        String u = raw.toLowerCase(Locale.ROOT).trim();
        return switch (u) {
            case "y", "yr", "yrs", "year", "years" -> ChronoUnit.YEARS;
            case "mo", "mon", "mons", "month", "months" -> ChronoUnit.MONTHS;
            case "w", "wk", "wks", "week", "weeks" -> ChronoUnit.WEEKS;
            case "d", "day", "days" -> ChronoUnit.DAYS;
            case "h", "hr", "hrs", "hour", "hours" -> ChronoUnit.HOURS;
            case "m", "min", "mins", "minute", "minutes" -> ChronoUnit.MINUTES;
            case "s", "sec", "secs", "second", "seconds" -> ChronoUnit.SECONDS;
            case "ms", "milli", "millis", "millisecond", "milliseconds" -> ChronoUnit.MILLIS;
            case "us", "µs", "μs", "micro", "micros", "microsecond", "microseconds" -> ChronoUnit.MICROS;
            case "ns", "nano", "nanos", "nanosecond", "nanoseconds" -> ChronoUnit.NANOS;
            default -> throw new IllegalArgumentException("Unknown unit: " + raw);
        };
    }

    private static void requireNoGap(Matcher m, int lastEnd, String parsedText, String originalExpr) {
        String gap = parsedText.substring(lastEnd, m.start()).trim();
        if (!gap.isEmpty()) throw badChunk(gap, originalExpr);
    }

    private static void requireNoTail(int lastEnd, String parsedText, String originalExpr) {
        String tail = parsedText.substring(lastEnd).trim();
        if (!tail.isEmpty()) throw badChunk(tail, originalExpr);
    }

    private static IllegalArgumentException badChunk(String chunk, String expr) {
        return new IllegalArgumentException("Bad delta chunk: \"" + chunk + "\" in \"" + expr + "\"");
    }

    private static String stripPrefix(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length()) ? text.substring(prefix.length()).trim() : text;
    }
}
