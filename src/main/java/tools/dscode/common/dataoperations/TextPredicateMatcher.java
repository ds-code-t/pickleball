package tools.dscode.common.dataoperations;

import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.assertions.ValueWrapper.ValueTypes;
import tools.dscode.common.domoperations.ExecutionDictionary;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;

public final class TextPredicateMatcher {
    private TextPredicateMatcher() {
    }

    public static boolean matches(Object candidate, TextOp textOp) {
        return matches(createValueWrapper(candidate), textOp);
    }

    public static boolean matches(ValueWrapper candidate, TextOp textOp) {
        ValueWrapper left = candidate == null ? createValueWrapper(null) : candidate;
        ValueWrapper right = textOp.text();
        ExecutionDictionary.Op op = textOp.op();

        return switch (op) {
            case DEFAULT, EQUALS -> compareStrings(left, right, StringMode.EQUALS);
            case CONTAINS -> compareStrings(left, right, StringMode.CONTAINS);
            case STARTS_WITH -> compareStrings(left, right, StringMode.STARTS_WITH);
            case ENDS_WITH -> compareStrings(left, right, StringMode.ENDS_WITH);
            case MATCHES -> Pattern.compile(right.toNonNullString())
                    .matcher(left.getValue() == null ? "" : left.getValue().toString())
                    .find();
            case GT -> compareNumbers(left, right) > 0;
            case GTE -> compareNumbers(left, right) >= 0;
            case LT -> compareNumbers(left, right) < 0;
            case LTE -> compareNumbers(left, right) <= 0;
            case HAS, HAS_NOT -> true;
        };
    }

    public static boolean matchesAll(Object candidate, Collection<TextOp> textOps) {
        if (textOps == null || textOps.isEmpty()) {
            return true;
        }
        for (TextOp textOp : textOps) {
            if (!matches(candidate, textOp)) {
                return false;
            }
        }
        return true;
    }

    private static int compareNumbers(ValueWrapper left, ValueWrapper right) {
        BigInteger first = left.asForcedSimpleNumber();
        BigInteger second = right.asForcedSimpleNumber();
        return first.compareTo(second);
    }

    private static boolean compareStrings(
            ValueWrapper left,
            ValueWrapper right,
            StringMode mode
    ) {
        ValueTypes type = right.type;
        String first;
        String second;
        boolean ignoreCase = false;

        switch (type) {
            case DOUBLE_QUOTED -> {
                first = left.asNormalizedText();
                second = right.asNormalizedText();
            }
            case SINGLE_QUOTED -> {
                first = left.asNormalizedText();
                second = right.asNormalizedText();
                ignoreCase = true;
            }
            case BACK_TICKED -> {
                first = left.getValue() == null ? "" : left.getValue().toString();
                second = right.getValue() == null ? "" : right.getValue().toString();
            }
            case TILDE_QUOTED -> {
                ValueWrapper leftStripped = left.stripAllNonLetters();
                ValueWrapper rightStripped = right.stripAllNonLetters();
                first = leftStripped == null ? "" : leftStripped.toNonNullString();
                second = rightStripped == null ? "" : rightStripped.toNonNullString();
                ignoreCase = true;
            }
            default -> {
                first = left.asNormalizedText();
                second = right.asNormalizedText();
            }
        }

        first = first == null ? "" : first;
        second = second == null ? "" : second;

        if (ignoreCase) {
            first = first.toLowerCase(Locale.ROOT);
            second = second.toLowerCase(Locale.ROOT);
        }

        return switch (mode) {
            case EQUALS -> first.equals(second);
            case CONTAINS -> first.contains(second);
            case STARTS_WITH -> first.startsWith(second);
            case ENDS_WITH -> first.endsWith(second);
        };
    }

    private enum StringMode {
        EQUALS,
        CONTAINS,
        STARTS_WITH,
        ENDS_WITH
    }
}
