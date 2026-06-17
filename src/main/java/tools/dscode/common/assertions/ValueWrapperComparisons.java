package tools.dscode.common.assertions;

import org.apache.commons.lang3.StringUtils;
import tools.dscode.common.util.datetime.TemporalValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

import static tools.dscode.common.reporting.logging.LogForwarder.logToDefaultLevel;


/**
 * Boolean comparison utilities for ValueWrapper.
 * <p>
 * Rules:
 * - String comparisons normally use asNormalizedText()
 * - If either arg is SINGLE_QUOTED → case-insensitive comparison
 * - If either arg is BACK_TICKED → use getValue().toString() for BOTH sides
 * - Numeric comparisons use asBigDecimal()
 * - Temporal comparisons use TemporalValue nanoseconds
 */
public final class ValueWrapperComparisons {

    private ValueWrapperComparisons() {
    }

    /* ---------------- logging helpers ---------------- */

    private static void logComparison(String comparisonType, Object left, Object right) {
        logToDefaultLevel(
                "checking " + comparisonType + " between: '" + left + "' and '" + right + "'"
        );
    }

    private static void logStringComparison(String comparisonType, ValueWrapper a, ValueWrapper b) {
        if (a == null || b == null) {
            logComparison(comparisonType, a, b);
            return;
        }

        logComparison(comparisonType, stringValue(a, b), stringValueOther(a, b));
    }

    private static void logNumericComparison(String comparisonType, ValueWrapper a, ValueWrapper b) {
        if (a == null || b == null) {
            logComparison(comparisonType, a, b);
            return;
        }

        logComparison(comparisonType, a.asBigDecimal(), b.asBigDecimal());
    }

    private static void logTemporalComparison(String comparisonType, ValueWrapper a, ValueWrapper b) {
        if (a == null || b == null) {
            logComparison(comparisonType, a, b);
            return;
        }

        logComparison(comparisonType, temporalLogValue(a), temporalLogValue(b));
    }

    /* ---------------- helpers ---------------- */

    private static boolean eitherTemporal(ValueWrapper a, ValueWrapper b) {
        return isTemporal(a) || isTemporal(b);
    }

    private static boolean isTemporal(ValueWrapper value) {
        if (value == null || value.type == null) return false;
        return switch (value.type.name()) {
            case "TIME_INSTANCE", "TIME_RANGE", "TIME_DURATION", "DATE_TIME", "DURATION", "DELTA" -> true;
            default -> false;
        };
    }

    private static boolean eitherNumeric(ValueWrapper a, ValueWrapper b) {
        return a.type == ValueWrapper.ValueTypes.NUMERIC
                || b.type == ValueWrapper.ValueTypes.NUMERIC;
    }

    private static boolean eitherSingleQuoted(ValueWrapper a, ValueWrapper b) {
        return a.type == ValueWrapper.ValueTypes.SINGLE_QUOTED
                || b.type == ValueWrapper.ValueTypes.SINGLE_QUOTED;
    }

    private static boolean eitherBackTicked(ValueWrapper a, ValueWrapper b) {
        return a.type == ValueWrapper.ValueTypes.BACK_TICKED
                || b.type == ValueWrapper.ValueTypes.BACK_TICKED;
    }

    private static String stringValue(ValueWrapper a, ValueWrapper b) {
        if (eitherBackTicked(a, b)) {
            return a.getValue() == null ? null : a.getValue().toString();
        }
        return a.asNormalizedText();
    }

    private static String stringValueOther(ValueWrapper a, ValueWrapper b) {
        if (eitherBackTicked(a, b)) {
            return b.getValue() == null ? null : b.getValue().toString();
        }
        return b.asNormalizedText();
    }

    private static boolean hasMargin(ValueWrapper margin) {
        return margin != null && !margin.isNullOrBlank();
    }

    private static boolean isNumericMargin(ValueWrapper margin) {
        return hasMargin(margin) && margin.isNumeric();
    }

    private static BigDecimal numericMargin(ValueWrapper margin) {
        return isNumericMargin(margin) ? margin.asBigDecimal().abs() : null;
    }

    private static boolean numericWithinMargin(ValueWrapper a, ValueWrapper b, ValueWrapper margin, boolean requirePositiveMargin) {
        BigDecimal marginValue = numericMargin(margin);
        if (marginValue == null) return false;
        if (requirePositiveMargin && marginValue.compareTo(BigDecimal.ZERO) <= 0) return false;

        return a.asBigDecimal().subtract(b.asBigDecimal()).abs().compareTo(marginValue) <= 0;
    }

    private static BigInteger temporalMarginNanos(ValueWrapper margin) {
        if (!hasMargin(margin)) return null;
        if (isTemporal(margin)) return temporalNanos(margin).abs();
        if (margin.isNumeric()) return margin.asBigDecimal().abs().toBigInteger();

        try {
            return TemporalValue.duration(margin.toString()).toNanos().abs();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean temporalWithinMargin(ValueWrapper a, ValueWrapper b, ValueWrapper margin, boolean requirePositiveMargin) {
        BigInteger marginNanos = temporalMarginNanos(margin);
        if (marginNanos == null) return false;
        if (requirePositiveMargin && marginNanos.signum() <= 0) return false;

        return temporalNanos(a).subtract(temporalNanos(b)).abs().compareTo(marginNanos) <= 0;
    }

    private static boolean equalsInternal(ValueWrapper a, ValueWrapper b) {
        return equalsInternal(a, b, null);
    }

    private static boolean equalsInternal(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        if (a == null || b == null) return a == b;
        if (eitherTemporal(a, b)) return temporalEqualsInternal(a, b, margin);
        if (eitherNumeric(a, b)) return numericEqualsInternal(a, b, margin);

        String left = stringValue(a, b);
        String right = stringValueOther(a, b);

        return eitherSingleQuoted(a, b)
                ? StringUtils.equalsIgnoreCase(left, right)
                : StringUtils.equals(left, right);
    }

    private static boolean numericEqualsInternal(ValueWrapper a, ValueWrapper b) {
        return numericEqualsInternal(a, b, null);
    }

    private static boolean numericEqualsInternal(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        if (a == null || b == null) return a == b;
        if (numericWithinMargin(a, b, margin, false)) return true;
        return a.asBigDecimal().compareTo(b.asBigDecimal()) == 0;
    }

    private static boolean temporalEqualsInternal(ValueWrapper a, ValueWrapper b) {
        return temporalEqualsInternal(a, b, null);
    }

    private static boolean temporalEqualsInternal(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        if (a == null || b == null) return a == b;
        if (temporalWithinMargin(a, b, margin, false)) return true;
        return temporalCompare(a, b) == 0;
    }

    private static int temporalCompare(ValueWrapper a, ValueWrapper b) {
        return temporalNanos(a).compareTo(temporalNanos(b));
    }

    private static BigInteger temporalNanos(ValueWrapper value) {
        return TemporalValue.fromValueWrapper(value).toNanos();
    }

    private static String temporalLogValue(ValueWrapper value) {
        TemporalValue temporalValue = TemporalValue.fromValueWrapper(value);
        return temporalValue + " (" + temporalValue.toNanos() + " ns)";
    }

    /* ---------------- string comparisons ---------------- */

    public static boolean notEquals(ValueWrapper a, ValueWrapper b) {
        return notEquals(a, b, null);
    }

    public static boolean notEquals(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        if (a != null && b != null && eitherTemporal(a, b)) {
            logTemporalComparison("temporal non-equality", a, b);
        } else if (a != null && b != null && eitherNumeric(a, b)) {
            logNumericComparison("numeric non-equality", a, b);
        } else {
            logStringComparison("non-equality", a, b);
        }

        return !equalsInternal(a, b, margin);
    }

    public static boolean equals(ValueWrapper a, ValueWrapper b) {
        return equals(a, b, null);
    }

    public static boolean equals(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        if (a != null && b != null && eitherTemporal(a, b)) {
            logTemporalComparison("temporal equality", a, b);
        } else if (a != null && b != null && eitherNumeric(a, b)) {
            logNumericComparison("numeric equality", a, b);
        } else {
            logStringComparison("equality", a, b);
        }

        return equalsInternal(a, b, margin);
    }

    public static boolean contains(ValueWrapper a, ValueWrapper b) {
        logStringComparison("contains", a, b);

        if (a == null || b == null) return false;

        String left = stringValue(a, b);
        String right = stringValueOther(a, b);

        return eitherSingleQuoted(a, b)
                ? StringUtils.containsIgnoreCase(left, right)
                : StringUtils.contains(left, right);
    }

    public static boolean startsWith(ValueWrapper a, ValueWrapper b) {
        logStringComparison("starts-with", a, b);

        if (a == null || b == null) return false;

        String left = stringValue(a, b);
        String right = stringValueOther(a, b);

        return eitherSingleQuoted(a, b)
                ? StringUtils.startsWithIgnoreCase(left, right)
                : StringUtils.startsWith(left, right);
    }

    public static boolean endsWith(ValueWrapper a, ValueWrapper b) {
        logStringComparison("ends-with", a, b);

        if (a == null || b == null) return false;

        String left = stringValue(a, b);
        String right = stringValueOther(a, b);

        return eitherSingleQuoted(a, b)
                ? StringUtils.endsWithIgnoreCase(left, right)
                : StringUtils.endsWith(left, right);
    }

    public static boolean matchesRegex(ValueWrapper a, ValueWrapper regex) {
        logStringComparison("regex match", a, regex);

        if (a == null || regex == null) return false;

        String value = stringValue(a, regex);

        String pattern = stringValueOther(a, regex);

        if (value == null || pattern == null) return false;

        int flags = eitherSingleQuoted(a, regex)
                ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                : 0;

        return Pattern.compile(pattern, flags).matcher(value).matches();
    }

    /* ---------------- numeric comparisons ---------------- */

    public static boolean numericEquals(ValueWrapper a, ValueWrapper b) {
        return numericEquals(a, b, null);
    }

    public static boolean numericEquals(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        if (a != null && b != null && eitherTemporal(a, b)) return temporalEquals(a, b, margin);

        logNumericComparison("numeric equality", a, b);
        return numericEqualsInternal(a, b, margin);
    }

    public static boolean numericGreaterThan(ValueWrapper a, ValueWrapper b) {
        return numericGreaterThan(a, b, null);
    }

    public static boolean numericGreaterThan(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        if (a != null && b != null && eitherTemporal(a, b)) return temporalGreaterThan(a, b, margin);

        logNumericComparison("numeric greater-than", a, b);

        if (a == null || b == null) return false;
        return a.asBigDecimal().compareTo(b.asBigDecimal()) > 0
                || numericWithinMargin(a, b, margin, true);
    }

    public static boolean numericLessThan(ValueWrapper a, ValueWrapper b) {
        return numericLessThan(a, b, null);
    }

    public static boolean numericLessThan(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        if (a != null && b != null && eitherTemporal(a, b)) return temporalLessThan(a, b, margin);

        logNumericComparison("numeric less-than", a, b);

        if (a == null || b == null) return false;
        return a.asBigDecimal().compareTo(b.asBigDecimal()) < 0
                || numericWithinMargin(a, b, margin, true);
    }

    public static boolean numericGreaterThanOrEqualTo(ValueWrapper a, ValueWrapper b) {
        return numericGreaterThanOrEqualTo(a, b, null);
    }

    public static boolean numericGreaterThanOrEqualTo(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        if (a != null && b != null && eitherTemporal(a, b)) return temporalGreaterThanOrEqualTo(a, b, margin);

        logNumericComparison("numeric greater-than-or-equal-to", a, b);

        if (a == null || b == null) return a == b;
        return a.asBigDecimal().compareTo(b.asBigDecimal()) >= 0
                || numericWithinMargin(a, b, margin, true);
    }

    public static boolean numericLessThanOrEqualTo(ValueWrapper a, ValueWrapper b) {
        return numericLessThanOrEqualTo(a, b, null);
    }

    public static boolean numericLessThanOrEqualTo(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        if (a != null && b != null && eitherTemporal(a, b)) return temporalLessThanOrEqualTo(a, b, margin);

        logNumericComparison("numeric less-than-or-equal-to", a, b);

        if (a == null || b == null) return a == b;
        return a.asBigDecimal().compareTo(b.asBigDecimal()) <= 0
                || numericWithinMargin(a, b, margin, true);
    }

    /* ---------------- temporal comparisons ---------------- */

    public static boolean temporalEquals(ValueWrapper a, ValueWrapper b) {
        return temporalEquals(a, b, null);
    }

    public static boolean temporalEquals(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        logTemporalComparison("temporal equality", a, b);
        return temporalEqualsInternal(a, b, margin);
    }

    public static boolean temporalGreaterThan(ValueWrapper a, ValueWrapper b) {
        return temporalGreaterThan(a, b, null);
    }

    public static boolean temporalGreaterThan(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        logTemporalComparison("temporal greater-than", a, b);

        if (a == null || b == null) return false;
        return temporalCompare(a, b) > 0
                || temporalWithinMargin(a, b, margin, true);
    }

    public static boolean temporalLessThan(ValueWrapper a, ValueWrapper b) {
        return temporalLessThan(a, b, null);
    }

    public static boolean temporalLessThan(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        logTemporalComparison("temporal less-than", a, b);

        if (a == null || b == null) return false;
        return temporalCompare(a, b) < 0
                || temporalWithinMargin(a, b, margin, true);
    }

    public static boolean temporalGreaterThanOrEqualTo(ValueWrapper a, ValueWrapper b) {
        return temporalGreaterThanOrEqualTo(a, b, null);
    }

    public static boolean temporalGreaterThanOrEqualTo(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        logTemporalComparison("temporal greater-than-or-equal-to", a, b);

        if (a == null || b == null) return a == b;
        return temporalCompare(a, b) >= 0
                || temporalWithinMargin(a, b, margin, true);
    }

    public static boolean temporalLessThanOrEqualTo(ValueWrapper a, ValueWrapper b) {
        return temporalLessThanOrEqualTo(a, b, null);
    }

    public static boolean temporalLessThanOrEqualTo(ValueWrapper a, ValueWrapper b, ValueWrapper margin) {
        logTemporalComparison("temporal less-than-or-equal-to", a, b);

        if (a == null || b == null) return a == b;
        return temporalCompare(a, b) <= 0
                || temporalWithinMargin(a, b, margin, true);
    }

}
