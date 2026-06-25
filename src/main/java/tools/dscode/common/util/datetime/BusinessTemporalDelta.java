package tools.dscode.common.util.datetime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calendar-aware delta terms for BusinessCalendar-bound date/time arithmetic.
 *
 * This intentionally sits beside TemporalDelta: unqualified units keep their
 * existing behavior, while "business <unit>" terms require a calendar context.
 */
final class BusinessTemporalDelta {

    private static final Pattern BUSINESS_UNIT = Pattern.compile("(?i)\\bbusiness\\s+[\\p{L}]+\\b");
    private static final Pattern TERM = Pattern.compile(
            "([+-])?\\s*((?:\\d+(?:\\.\\d*)?)|(?:\\.\\d+))\\s*(?:(business)\\s+)?([\\p{L}]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

    private BusinessTemporalDelta() {}

    static boolean hasBusinessTerms(String expr) {
        return expr != null && BUSINESS_UNIT.matcher(expr).find();
    }

    static ZonedDateTime applyTo(BusinessCalendar calendar, ZonedDateTime start, String expr) {
        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(start, "start");

        ZonedDateTime cur = start;
        for (Term term : parse(expr)) {
            if (term.amount().compareTo(BigDecimal.ZERO) == 0) continue;

            if (term.business()) {
                cur = applyBusinessTerm(calendar, cur, term);
            } else {
                cur = TemporalDelta.of(List.of(new TemporalDelta.Term(term.amount(), term.unit()))).applyTo(cur);
            }
        }
        return cur;
    }

    private static ZonedDateTime applyBusinessTerm(BusinessCalendar calendar, ZonedDateTime cur, Term term) {
        if (isBusinessDateUnit(term.unit())) {
            if (!term.isWholeNumber()) {
                throw new IllegalArgumentException("Business date units must be whole numbers: " + renderTerm(term));
            }
            return calendar.addBusinessDateUnits(cur, term.wholeAmountExact(), term.unit());
        }

        if (isBusinessTimeUnit(term.unit())) {
            ZonedDateTime out = calendar.addOpenDuration(cur, exactDuration(term));
            if (out == null) {
                throw new IllegalStateException("No open time found while applying business delta term: " + renderTerm(term));
            }
            return out;
        }

        throw new IllegalArgumentException("Unsupported business unit: " + term.unit());
    }

    private static List<Term> parse(String expr) {
        Objects.requireNonNull(expr, "expr");

        String raw = stripPrefix(stripPrefix(expr.trim(), "Duration:"), "Delta:")
                .replaceAll("(?i)\\band\\b", " ")
                .replace(',', ' ')
                .trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Delta expression is blank");

        String parsedText = withLeadingSign(raw);
        List<Term> out = new ArrayList<>();
        Matcher matcher = TERM.matcher(parsedText);
        int lastEnd = 0;
        char carrySign = '+';

        while (matcher.find()) {
            requireNoGap(matcher, lastEnd, parsedText, expr);
            lastEnd = matcher.end();

            if (matcher.group(1) != null && !matcher.group(1).isBlank()) {
                carrySign = matcher.group(1).charAt(0);
            }

            BigDecimal amount = new BigDecimal(matcher.group(2));
            if (carrySign == '-') amount = amount.negate();

            out.add(new Term(
                    amount,
                    DateTimeDeltaParsingUtils.normalizeUnit(matcher.group(4)),
                    matcher.group(3) != null
            ));
        }

        requireNoTail(lastEnd, parsedText, expr);
        if (out.isEmpty()) throw badChunk(expr, expr);
        return List.copyOf(out);
    }

    private static boolean isBusinessDateUnit(ChronoUnit unit) {
        return unit == ChronoUnit.YEARS
                || unit == ChronoUnit.MONTHS
                || unit == ChronoUnit.WEEKS
                || unit == ChronoUnit.DAYS;
    }

    private static boolean isBusinessTimeUnit(ChronoUnit unit) {
        return unit == ChronoUnit.HOURS
                || unit == ChronoUnit.MINUTES
                || unit == ChronoUnit.SECONDS
                || unit == ChronoUnit.MILLIS
                || unit == ChronoUnit.MICROS
                || unit == ChronoUnit.NANOS;
    }

    private static Duration exactDuration(Term term) {
        BigInteger nanos = term.amount()
                .multiply(new BigDecimal(nanosPerUnit(term.unit())))
                .setScale(0, RoundingMode.UNNECESSARY)
                .toBigIntegerExact();
        BigInteger[] secondsAndNanos = nanos.divideAndRemainder(NANOS_PER_SECOND);
        return Duration.ofSeconds(secondsAndNanos[0].longValueExact(), secondsAndNanos[1].longValueExact());
    }

    private static BigInteger nanosPerUnit(ChronoUnit unit) {
        return switch (unit) {
            case HOURS -> BigInteger.valueOf(3600L * 1_000_000_000L);
            case MINUTES -> BigInteger.valueOf(60L * 1_000_000_000L);
            case SECONDS -> BigInteger.valueOf(1_000_000_000L);
            case MILLIS -> BigInteger.valueOf(1_000_000L);
            case MICROS -> BigInteger.valueOf(1_000L);
            case NANOS -> BigInteger.ONE;
            default -> throw new IllegalArgumentException("Unsupported exact business-time unit: " + unit);
        };
    }

    private static String withLeadingSign(String s) {
        return s.isEmpty() || s.charAt(0) == '+' || s.charAt(0) == '-' ? s : "+ " + s;
    }

    private static void requireNoGap(Matcher matcher, int lastEnd, String parsedText, String originalExpr) {
        String gap = parsedText.substring(lastEnd, matcher.start()).trim();
        if (!gap.isEmpty()) throw badChunk(gap, originalExpr);
    }

    private static void requireNoTail(int lastEnd, String parsedText, String originalExpr) {
        String tail = parsedText.substring(lastEnd).trim();
        if (!tail.isEmpty()) throw badChunk(tail, originalExpr);
    }

    private static IllegalArgumentException badChunk(String chunk, String expr) {
        return new IllegalArgumentException("Bad delta chunk: \"" + chunk + "\" in \"" + expr + "\"");
    }

    private static String renderTerm(Term term) {
        BigDecimal abs = term.amount().abs().stripTrailingZeros();
        String sign = term.amount().signum() < 0 ? "-" : "+";
        return sign + " " + abs.toPlainString() + " business " + term.unit().toString().toLowerCase(Locale.ROOT);
    }

    private static String stripPrefix(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length()) ? text.substring(prefix.length()).trim() : text;
    }

    private record Term(BigDecimal amount, ChronoUnit unit, boolean business) {
        private Term {
            amount = Objects.requireNonNull(amount, "amount").stripTrailingZeros();
            unit = Objects.requireNonNull(unit, "unit");
        }

        private boolean isWholeNumber() {
            return amount.stripTrailingZeros().scale() <= 0;
        }

        private long wholeAmountExact() {
            return amount.longValueExact();
        }
    }
}
