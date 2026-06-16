package tools.dscode.common.util.datetime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Ordered calendar/time delta. Unlike java.time.Duration, this can preserve
 * calendar-relative years/months and apply them to a concrete ZonedDateTime.
 */
public final class TemporalDelta {

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

    private final List<Term> terms;

    private TemporalDelta(List<Term> terms) {
        this.terms = List.copyOf(terms);
    }

    public record Term(BigDecimal amount, ChronoUnit unit) {
        public Term {
            amount = Objects.requireNonNull(amount, "amount").stripTrailingZeros();
            unit = Objects.requireNonNull(unit, "unit");
        }

        boolean isWholeNumber() {
            return amount.stripTrailingZeros().scale() <= 0;
        }

        long wholeAmountExact() {
            return amount.longValueExact();
        }
    }

    public static TemporalDelta of(List<Term> terms) {
        Objects.requireNonNull(terms, "terms");
        return new TemporalDelta(terms);
    }

    public static TemporalDelta fromDuration(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero()) return new TemporalDelta(List.of());

        boolean negative = duration.isNegative();
        Duration abs = duration.abs();
        List<Term> out = new ArrayList<>();
        add(out, abs.toDays(), ChronoUnit.DAYS, negative);
        add(out, abs.toHoursPart(), ChronoUnit.HOURS, negative);
        add(out, abs.toMinutesPart(), ChronoUnit.MINUTES, negative);
        add(out, abs.toSecondsPart(), ChronoUnit.SECONDS, negative);

        int nanos = abs.toNanosPart();
        add(out, nanos / 1_000_000, ChronoUnit.MILLIS, negative);
        nanos %= 1_000_000;
        add(out, nanos / 1_000, ChronoUnit.MICROS, negative);
        add(out, nanos % 1_000, ChronoUnit.NANOS, negative);
        return new TemporalDelta(out);
    }

    public static TemporalDelta parse(String spec) {
        return parse(spec, List.of());
    }

    public static TemporalDelta parse(String spec, List<String> patterns) {
        Objects.requireNonNull(spec, "spec");
        String raw = stripPrefix(stripPrefix(spec.trim(), "Duration:"), "Delta:").trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Delta expression is blank");

        List<String> attempts = patterns == null || patterns.isEmpty() ? List.of("*") : patterns;
        RuntimeException last = null;

        for (String pattern : attempts) {
            String p = pattern == null ? "" : pattern.trim();
            if (p.isEmpty()) continue;

            if ("*".equals(p)) {
                try { return parseImplicit(raw); } catch (RuntimeException e) { last = e; }
                continue;
            }

            try {
                return parseWithNamedOrPattern(raw, p);
            } catch (RuntimeException e) {
                last = e;
            }
        }

        throw new IllegalArgumentException("Unparseable delta: \"" + spec + "\"", last);
    }

    public List<Term> terms() {
        return terms;
    }

    public ZonedDateTime applyTo(ZonedDateTime start) {
        Objects.requireNonNull(start, "start");
        ZonedDateTime cur = start;

        for (Term term : terms) {
            if (term.amount().compareTo(BigDecimal.ZERO) == 0) continue;

            if (term.unit() == ChronoUnit.YEARS || term.unit() == ChronoUnit.MONTHS) {
                if (!term.isWholeNumber()) {
                    throw new IllegalArgumentException("Delta cannot include fractional months/years: " + this);
                }
                cur = cur.plus(term.wholeAmountExact(), term.unit());
                continue;
            }

            if ((term.unit() == ChronoUnit.WEEKS || term.unit() == ChronoUnit.DAYS) && term.isWholeNumber()) {
                cur = cur.plus(term.wholeAmountExact(), term.unit());
                continue;
            }

            cur = cur.plus(exactDuration(term));
        }

        return cur;
    }

    public Duration toDuration() {
        ZonedDateTime start = ZonedDateTime.now(ZoneOffset.UTC);
        return Duration.between(start, applyTo(start));
    }

    public String toIsoString() {
        return toDuration().toString();
    }

    public String render(String format) {
        String f = (format == null || format.isBlank())
                ? "friendly"
                : format.trim().toLowerCase(Locale.ROOT).replace('-', '_');

        return switch (f) {
            case "friendly", "delta" -> toFriendlyString();
            case "iso", "iso_8601", "iso8601" -> toIsoString();
            case "nanos", "nanoseconds", "ns" -> nanos(toDuration()).toString();
            case "micros", "microseconds", "us" -> decimalUnit(toDuration(), BigDecimal.valueOf(1_000));
            case "millis", "milliseconds", "ms" -> decimalUnit(toDuration(), BigDecimal.valueOf(1_000_000));
            case "seconds", "second", "secs", "sec", "s" -> decimalUnit(toDuration(), BigDecimal.valueOf(1_000_000_000L));
            case "minutes", "minute", "mins", "min", "m" -> decimalUnit(toDuration(), BigDecimal.valueOf(60L * 1_000_000_000L));
            case "hours", "hour", "hrs", "hr", "h" -> decimalUnit(toDuration(), BigDecimal.valueOf(3600L * 1_000_000_000L));
            case "days", "day", "d" -> decimalUnit(toDuration(), BigDecimal.valueOf(86_400L * 1_000_000_000L));
            case "hh:mm", "h:mm" -> formatClock(toDuration(), false);
            case "hh:mm:ss", "h:mm:ss" -> formatClock(toDuration(), true);
            case "human" -> human(toDuration());
            default -> renderPattern(format);
        };
    }

    public String toFriendlyString() {
        if (terms.isEmpty()) return "+ 0 seconds";

        List<String> parts = new ArrayList<>();
        for (Term term : terms) {
            if (term.amount().compareTo(BigDecimal.ZERO) == 0) continue;
            BigDecimal abs = term.amount().abs().stripTrailingZeros();
            String sign = term.amount().signum() < 0 ? "-" : "+";
            String unit = friendlyUnit(term.unit(), abs.compareTo(BigDecimal.ONE) == 0);
            parts.add(sign + " " + abs.toPlainString() + " " + unit);
        }
        return parts.isEmpty() ? "+ 0 seconds" : String.join(" ", parts);
    }

    @Override
    public String toString() {
        return toFriendlyString();
    }

    private static TemporalDelta parseImplicit(String raw) {
        RuntimeException last;
        try { return parseFriendly(raw); } catch (RuntimeException e) { last = e; }
        try { return parseIso(raw); } catch (RuntimeException e) { last = e; }
        try { return parseColon(raw); } catch (RuntimeException e) { last = e; }
        throw last;
    }

    private static TemporalDelta parseWithNamedOrPattern(String raw, String pattern) {
        String p = pattern.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (p) {
            case "friendly", "delta" -> parseFriendly(raw);
            case "iso", "iso_8601", "iso8601" -> parseIso(raw);
            case "colon", "clock", "h:mm:ss", "hh:mm:ss", "d:h:mm:ss", "y:m:d:h:mm:ss" -> parseColon(raw);
            default -> parsePattern(raw, pattern);
        };
    }

    private static TemporalDelta parseFriendly(String raw) {
        String normalized = raw.replaceAll("(?i)\\band\\b", " ").replace(',', ' ').trim();
        List<Term> out = new ArrayList<>();
        for (DateTimeDeltaParsingUtils.Delta delta : DateTimeDeltaParsingUtils.parse(normalized)) {
            out.add(new Term(delta.amount(), delta.unit()));
        }
        return new TemporalDelta(out);
    }

    private static TemporalDelta parseIso(String raw) {
        String s = raw.trim().replaceAll("\\s+", "");
        int externalSign = 1;
        if (s.startsWith("+")) {
            s = s.substring(1);
        } else if (s.startsWith("-")) {
            externalSign = -1;
            s = s.substring(1);
        }
        if (s.isEmpty()) throw new IllegalArgumentException("ISO delta is blank");

        try {
            Duration d = Duration.parse(s.toUpperCase(Locale.ROOT));
            if (externalSign < 0) d = d.negated();
            return fromDuration(d);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid ISO-8601 duration: \"" + raw + "\"", e);
        }
    }

    private static TemporalDelta parseColon(String raw) {
        String s = raw.trim();
        int sign = 1;
        if (s.startsWith("+")) {
            s = s.substring(1).trim();
        } else if (s.startsWith("-")) {
            sign = -1;
            s = s.substring(1).trim();
        }

        String[] parts = s.split(":");
        ChronoUnit[] units = switch (parts.length) {
            case 2 -> new ChronoUnit[] { ChronoUnit.MINUTES, ChronoUnit.SECONDS };
            case 3 -> new ChronoUnit[] { ChronoUnit.HOURS, ChronoUnit.MINUTES, ChronoUnit.SECONDS };
            case 4 -> new ChronoUnit[] { ChronoUnit.DAYS, ChronoUnit.HOURS, ChronoUnit.MINUTES, ChronoUnit.SECONDS };
            case 6 -> new ChronoUnit[] { ChronoUnit.YEARS, ChronoUnit.MONTHS, ChronoUnit.DAYS, ChronoUnit.HOURS, ChronoUnit.MINUTES, ChronoUnit.SECONDS };
            default -> throw new IllegalArgumentException("Unsupported colon delta shape: \"" + raw + "\"");
        };

        List<Term> out = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (!p.matches("\\d+(?:\\.\\d+)?")) throw new IllegalArgumentException("Bad colon delta part: \"" + p + "\"");
            BigDecimal amount = new BigDecimal(p).multiply(BigDecimal.valueOf(sign));
            if (amount.compareTo(BigDecimal.ZERO) != 0) out.add(new Term(amount, units[i]));
        }
        return new TemporalDelta(out);
    }

    private static TemporalDelta parsePattern(String raw, String pattern) {
        PatternCursor cursor = new PatternCursor(raw, pattern);
        List<Term> out = new ArrayList<>();
        int sign = cursor.consumeGlobalSign();

        while (!cursor.patternDone()) {
            char ch = cursor.patternChar();
            if (ch == '\'') {
                cursor.consumeQuotedLiteral();
                continue;
            }
            if (isPatternField(ch)) {
                int count = cursor.consumeRun(ch);
                ChronoUnit unit = unitForPattern(ch);
                if (unit == null) {
                    cursor.consumeZone();
                    continue;
                }
                BigDecimal value = cursor.consumeNumber(count).multiply(BigDecimal.valueOf(sign));
                if (value.compareTo(BigDecimal.ZERO) != 0) out.add(new Term(value, unit));
                continue;
            }
            cursor.consumeLiteral();
        }

        cursor.requireInputDone();
        return new TemporalDelta(out);
    }

    private String renderPattern(String pattern) {
        StringBuilder out = new StringBuilder();
        PatternRenderCursor cursor = new PatternRenderCursor(pattern, aggregateTerms());

        while (!cursor.done()) {
            char ch = cursor.patternChar();
            if (ch == '\'') {
                cursor.appendQuotedLiteral(out);
                continue;
            }
            if (isPatternField(ch)) {
                int count = cursor.consumeRun(ch);
                ChronoUnit unit = unitForPattern(ch);
                if (unit == null) {
                    cursor.appendZone(out, count);
                } else {
                    cursor.appendNumber(out, unit, count);
                }
                continue;
            }
            out.append(ch);
            cursor.advance();
        }

        return out.toString();
    }

    private BigDecimal[] aggregateTerms() {
        BigDecimal[] values = new BigDecimal[ChronoUnit.values().length];
        for (int i = 0; i < values.length; i++) values[i] = BigDecimal.ZERO;
        for (Term term : terms) {
            values[term.unit().ordinal()] = values[term.unit().ordinal()].add(term.amount());
        }
        return values;
    }

    private static boolean isPatternField(char ch) {
        return "uyMdDHmsSXxZzVO".indexOf(ch) >= 0;
    }

    private static ChronoUnit unitForPattern(char ch) {
        return switch (ch) {
            case 'u', 'y' -> ChronoUnit.YEARS;
            case 'M' -> ChronoUnit.MONTHS;
            case 'd', 'D' -> ChronoUnit.DAYS;
            case 'H' -> ChronoUnit.HOURS;
            case 'm' -> ChronoUnit.MINUTES;
            case 's' -> ChronoUnit.SECONDS;
            case 'S' -> ChronoUnit.NANOS;
            default -> null;
        };
    }

    private static Duration exactDuration(Term term) {
        BigInteger nanos = term.amount().multiply(new BigDecimal(nanosPerUnit(term.unit())))
                .setScale(0, RoundingMode.UNNECESSARY)
                .toBigIntegerExact();
        BigInteger[] secondsAndNanos = nanos.divideAndRemainder(NANOS_PER_SECOND);
        return Duration.ofSeconds(secondsAndNanos[0].longValueExact(), secondsAndNanos[1].longValueExact());
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

    private static void add(List<Term> terms, long value, ChronoUnit unit, boolean negative) {
        if (value == 0) return;
        BigDecimal amount = BigDecimal.valueOf(negative ? -value : value);
        terms.add(new Term(amount, unit));
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
        addHuman(parts, abs.toDays(), "day");
        addHuman(parts, abs.toHoursPart(), "hour");
        addHuman(parts, abs.toMinutesPart(), "minute");
        addHuman(parts, abs.toSecondsPart(), "second");
        addHuman(parts, abs.toMillisPart(), "millisecond");

        int remainingNanos = abs.toNanosPart() % 1_000_000;
        addHuman(parts, remainingNanos / 1_000, "microsecond");
        addHuman(parts, remainingNanos % 1_000, "nanosecond");

        String out = String.join(" ", parts);
        return negative ? "-" + out : out;
    }

    private static void addHuman(List<String> parts, long value, String unit) {
        if (value != 0) parts.add(value + " " + unit + (value == 1 ? "" : "s"));
    }

    private static String friendlyUnit(ChronoUnit unit, boolean singular) {
        String base = switch (unit) {
            case YEARS -> "year";
            case MONTHS -> "month";
            case WEEKS -> "week";
            case DAYS -> "day";
            case HOURS -> "hour";
            case MINUTES -> "minute";
            case SECONDS -> "second";
            case MILLIS -> "milli";
            case MICROS -> "micro";
            case NANOS -> "nano";
            default -> unit.toString().toLowerCase(Locale.ROOT);
        };
        return singular ? base : base + "s";
    }

    private static String stripPrefix(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length()) ? text.substring(prefix.length()).trim() : text;
    }

    private static final class PatternCursor {
        private final String input;
        private final String pattern;
        private int inputPos;
        private int patternPos;

        PatternCursor(String input, String pattern) {
            this.input = input.trim();
            this.pattern = pattern;
        }

        int consumeGlobalSign() {
            if (inputPos < input.length() && input.charAt(inputPos) == '+') {
                inputPos++;
                skipInputWhitespace();
                return 1;
            }
            if (inputPos < input.length() && input.charAt(inputPos) == '-') {
                inputPos++;
                skipInputWhitespace();
                return -1;
            }
            return 1;
        }

        boolean patternDone() { return patternPos >= pattern.length(); }
        char patternChar() { return pattern.charAt(patternPos); }

        int consumeRun(char ch) {
            int start = patternPos;
            while (patternPos < pattern.length() && pattern.charAt(patternPos) == ch) patternPos++;
            return patternPos - start;
        }

        void consumeQuotedLiteral() {
            patternPos++;
            while (patternPos < pattern.length()) {
                char ch = pattern.charAt(patternPos++);
                if (ch == '\'') return;
                consumeExpected(ch);
            }
            throw new IllegalArgumentException("Unterminated quoted literal in pattern: " + pattern);
        }

        void consumeLiteral() {
            consumeExpected(pattern.charAt(patternPos++));
        }

        BigDecimal consumeNumber(int width) {
            int start = inputPos;
            int max = width <= 1 ? input.length() : Math.min(input.length(), inputPos + width);
            while (inputPos < max && Character.isDigit(input.charAt(inputPos))) inputPos++;
            if (start == inputPos) throw new IllegalArgumentException("Expected number at index " + start + " in \"" + input + "\"");
            return new BigDecimal(input.substring(start, inputPos));
        }

        void consumeZone() {
            if (inputPos >= input.length()) return;
            if (input.charAt(inputPos) == 'Z') {
                inputPos++;
                return;
            }
            if (input.charAt(inputPos) == '+' || input.charAt(inputPos) == '-') {
                inputPos++;
                while (inputPos < input.length()
                        && (Character.isDigit(input.charAt(inputPos)) || input.charAt(inputPos) == ':')) {
                    inputPos++;
                }
                return;
            }
            while (inputPos < input.length() && !Character.isWhitespace(input.charAt(inputPos))) inputPos++;
        }

        void requireInputDone() {
            skipInputWhitespace();
            if (inputPos != input.length()) {
                throw new IllegalArgumentException("Unexpected text at index " + inputPos + " in \"" + input + "\"");
            }
        }

        private void consumeExpected(char expected) {
            if (Character.isWhitespace(expected)) {
                skipInputWhitespace();
                return;
            }
            if (inputPos >= input.length() || input.charAt(inputPos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at index " + inputPos + " in \"" + input + "\"");
            }
            inputPos++;
        }

        private void skipInputWhitespace() {
            while (inputPos < input.length() && Character.isWhitespace(input.charAt(inputPos))) inputPos++;
        }
    }

    private static final class PatternRenderCursor {
        private final String pattern;
        private final BigDecimal[] values;
        private int patternPos;

        PatternRenderCursor(String pattern, BigDecimal[] values) {
            this.pattern = pattern;
            this.values = values;
        }

        boolean done() { return patternPos >= pattern.length(); }
        char patternChar() { return pattern.charAt(patternPos); }
        void advance() { patternPos++; }

        int consumeRun(char ch) {
            int start = patternPos;
            while (patternPos < pattern.length() && pattern.charAt(patternPos) == ch) patternPos++;
            return patternPos - start;
        }

        void appendQuotedLiteral(StringBuilder out) {
            patternPos++;
            while (patternPos < pattern.length()) {
                char ch = pattern.charAt(patternPos++);
                if (ch == '\'') return;
                out.append(ch);
            }
            throw new IllegalArgumentException("Unterminated quoted literal in pattern: " + pattern);
        }

        void appendZone(StringBuilder out, int count) {
            out.append("Z");
        }

        void appendNumber(StringBuilder out, ChronoUnit unit, int width) {
            BigDecimal value = values[unit.ordinal()].stripTrailingZeros();
            long whole = value.longValue();
            String sign = whole < 0 ? "-" : "";
            String digits = Long.toString(Math.abs(whole));
            int minWidth = width <= 1 ? 1 : width;
            if (digits.length() < minWidth) digits = "0".repeat(minWidth - digits.length()) + digits;
            out.append(sign).append(digits);
        }
    }
}
