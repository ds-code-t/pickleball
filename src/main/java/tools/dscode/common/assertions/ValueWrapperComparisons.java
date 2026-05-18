package tools.dscode.common.assertions;

import org.apache.commons.lang3.StringUtils;
import tools.dscode.common.seleniumextensions.ElementWrapper;

import java.util.regex.Pattern;

import static tools.dscode.common.reporting.logging.LogForwarder.closestEntryToPhrase;

// Adjust this import to wherever closestEntryToPhrase() lives in your project.
// import static your.package.YourLoggerClass.closestEntryToPhrase;

/**
 * Boolean comparison utilities for ValueWrapper.
 * <p>
 * Rules:
 * - String comparisons normally use asNormalizedText()
 * - If either arg is SINGLE_QUOTED → case-insensitive comparison
 * - If either arg is BACK_TICKED → use getValue().toString() for BOTH sides
 * - Numeric comparisons use asBigInteger()
 */
public final class ValueWrapperComparisons {

    private ValueWrapperComparisons() {
    }

    /* ---------------- logging helpers ---------------- */

    private static void logComparison(String comparisonType, Object left, Object right) {
        closestEntryToPhrase().info(
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

    /* ---------------- helpers ---------------- */

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

    private static boolean equalsInternal(ValueWrapper a, ValueWrapper b) {
        if (a == null || b == null) return a == b;
        if (eitherNumeric(a, b)) return numericEqualsInternal(a, b);

        String left = stringValue(a, b);
        String right = stringValueOther(a, b);

        return eitherSingleQuoted(a, b)
                ? StringUtils.equalsIgnoreCase(left, right)
                : StringUtils.equals(left, right);
    }

    private static boolean numericEqualsInternal(ValueWrapper a, ValueWrapper b) {
        if (a == null || b == null) return a == b;
        return a.asBigDecimal().compareTo(b.asBigDecimal()) == 0;
    }

    /* ---------------- string comparisons ---------------- */

    public static boolean notEquals(ValueWrapper a, ValueWrapper b) {
        logStringComparison("non-equality", a, b);
        return !equalsInternal(a, b);
    }

    public static boolean equals(ValueWrapper a, ValueWrapper b) {
        if (a != null && b != null && eitherNumeric(a, b)) {
            logNumericComparison("numeric equality", a, b);
        } else {
            logStringComparison("equality", a, b);
        }

        return equalsInternal(a, b);
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
        logNumericComparison("numeric equality", a, b);
        return numericEqualsInternal(a, b);
    }

    public static boolean numericGreaterThan(ValueWrapper a, ValueWrapper b) {
        logNumericComparison("numeric greater-than", a, b);

        if (a == null || b == null) return false;
        return a.asBigDecimal().compareTo(b.asBigDecimal()) > 0;
    }

    public static boolean numericLessThan(ValueWrapper a, ValueWrapper b) {
        logNumericComparison("numeric less-than", a, b);

        if (a == null || b == null) return false;
        return a.asBigDecimal().compareTo(b.asBigDecimal()) < 0;
    }

    public static boolean numericGreaterThanOrEqualTo(ValueWrapper a, ValueWrapper b) {
        logNumericComparison("numeric greater-than-or-equal-to", a, b);

        if (a == null || b == null) return a == b;
        return a.asBigDecimal().compareTo(b.asBigDecimal()) >= 0;
    }

    public static boolean numericLessThanOrEqualTo(ValueWrapper a, ValueWrapper b) {
        logNumericComparison("numeric less-than-or-equal-to", a, b);

        if (a == null || b == null) return a == b;
        return a.asBigDecimal().compareTo(b.asBigDecimal()) <= 0;
    }


}