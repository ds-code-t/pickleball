package tools.dscode.common.assertions;

import org.apache.commons.lang3.StringUtils;
import tools.dscode.common.seleniumextensions.ElementWrapper;

import java.util.regex.Pattern;

/**
 * Boolean comparison utilities for ValueWrapper.
 *
 * Rules:
 * - String comparisons normally use asNormalizedText()
 * - If either arg is SINGLE_QUOTED → case-insensitive comparison
 * - If either arg is BACK_TICKED → use getValue().toString() for BOTH sides
 * - Numeric comparisons use asBigInteger()
 */
public final class ValueWrapperComparisons {

    private ValueWrapperComparisons() {}

    /* ---------------- helpers ---------------- */

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

    /* ---------------- string comparisons ---------------- */

    public static boolean notEquals(ValueWrapper a, ValueWrapper b) {
        if (a == null || b == null) return a == b;

        String left = stringValue(a, b);
        String right = stringValueOther(a, b);

        return eitherSingleQuoted(a, b)
                ? !StringUtils.equalsIgnoreCase(left, right)
                : !StringUtils.equals(left, right);
    }

    public static boolean equals(ValueWrapper a, ValueWrapper b) {
        if (a == null || b == null) return a == b;

        String left = stringValue(a, b);
        String right = stringValueOther(a, b);


        boolean returnBool = eitherSingleQuoted(a, b)
                ? StringUtils.equalsIgnoreCase(left, right)
                : StringUtils.equals(left, right);


        return eitherSingleQuoted(a, b)
                ? StringUtils.equalsIgnoreCase(left, right)
                : StringUtils.equals(left, right);
    }

    public static boolean contains(ValueWrapper a, ValueWrapper b) {
        if (a == null || b == null) return false;

        String left = stringValue(a, b);
        String right = stringValueOther(a, b);

        return eitherSingleQuoted(a, b)
                ? StringUtils.containsIgnoreCase(left, right)
                : StringUtils.contains(left, right);
    }

    public static boolean startsWith(ValueWrapper a, ValueWrapper b) {
        if (a == null || b == null) return false;

        String left = stringValue(a, b);
        String right = stringValueOther(a, b);

        return eitherSingleQuoted(a, b)
                ? StringUtils.startsWithIgnoreCase(left, right)
                : StringUtils.startsWith(left, right);
    }

    public static boolean endsWith(ValueWrapper a, ValueWrapper b) {
        if (a == null || b == null) return false;

        String left = stringValue(a, b);
        String right = stringValueOther(a, b);

        return eitherSingleQuoted(a, b)
                ? StringUtils.endsWithIgnoreCase(left, right)
                : StringUtils.endsWith(left, right);
    }

    public static boolean matchesRegex(ValueWrapper a, ValueWrapper regex) {

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
        if (a == null || b == null) return a == b;
        return a.asBigInteger().compareTo(b.asBigInteger()) == 0;
    }

    public static boolean numericGreaterThan(ValueWrapper a, ValueWrapper b) {
        if (a == null || b == null) return false;
        return a.asBigInteger().compareTo(b.asBigInteger()) > 0;
    }

    public static boolean numericLessThan(ValueWrapper a, ValueWrapper b) {
        if (a == null || b == null) return false;
        return a.asBigInteger().compareTo(b.asBigInteger()) < 0;
    }

//    /* ---------------- Single Argument ---------------- */


}
