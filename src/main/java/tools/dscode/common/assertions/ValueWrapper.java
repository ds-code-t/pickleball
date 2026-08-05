package tools.dscode.common.assertions;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.LinkedListMultimap;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import com.google.common.collect.ListMultimap;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import tools.dscode.common.util.datetime.BusinessTimeRange;
import tools.dscode.common.util.datetime.DurationFormattingUtils;
import tools.dscode.common.util.datetime.TemporalValue;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.evaluations.AviatorUtil.isStringTruthy;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.normalizeText;

public class ValueWrapper {

    public final ValueTypes type;
    private final Object value;
    private final String normalizedText;

    @Override
    public boolean equals(Object o) {
        if (value == null && o == null) return true;
        if (o instanceof ValueWrapper valueWrapper) {
            if (value == valueWrapper.value)
                return true;
        }
        if (value == null || o == null) return false;
        if (value.equals(o))
            return true;
        ValueWrapper other = o instanceof ValueWrapper vw ? vw : createValueWrapper(o);
        if (isNumeric() || other.isNumeric()) {
            return isNumeric()
                    && other.isNumeric()
                    && asBigDecimal().compareTo(other.asBigDecimal()) == 0;
        }
        String normalizedInput = normalizeText(o.toString());
        return value.equals(o) || Objects.equals(normalizedText, normalizedInput);
    }

    public Object getValue() {
        return value;
    }

    public enum ValueTypes {
        DOUBLE_QUOTED, SINGLE_QUOTED, BACK_TICKED, TILDE_QUOTED,
        NUMERIC, DEFAULT, BOOLEAN, DURATION, DATE_TIME, TIME_RANGE,
        LIST, SET, MAP, MULTIMAP
    }

    public static ValueWrapper createValueWrapper(Object obj, ValueTypes type) {
        return new ValueWrapper(obj, type);
    }

    public static ValueWrapper createValueWrapper(Object obj) {
        if (obj == null || obj instanceof String)
            return new ValueWrapper((String) obj);
        if (obj instanceof Number)
            return new ValueWrapper(obj, ValueTypes.NUMERIC);
        if (obj instanceof Duration duration)
            return new ValueWrapper(duration, ValueTypes.DURATION, durationSyntax(duration));
        if (obj instanceof Instant instant)
            return new ValueWrapper(instant, ValueTypes.DATE_TIME, instant.toString());
        if (obj instanceof BusinessTimeRange timeRange)
            return new ValueWrapper(timeRange, ValueTypes.TIME_RANGE, timeRange.toCanonicalString());
        if (obj instanceof TemporalValue temporalValue)
            return switch (temporalValue.kind()) {
                case DATE_TIME -> new ValueWrapper(
                        temporalValue.requireBusinessTime().value().toInstant(),
                        ValueTypes.DATE_TIME,
                        temporalValue.toString()
                );
                case DELTA -> new ValueWrapper(
                        temporalValue.requireDuration(),
                        ValueTypes.DURATION,
                        temporalValue.toString()
                );
                case TIME_RANGE -> new ValueWrapper(
                        temporalValue.requireTimeRange(),
                        ValueTypes.TIME_RANGE,
                        temporalValue.requireTimeRange().toCanonicalString()
                );
                case TEXT -> new ValueWrapper(temporalValue.requireText());
                case BOOLEAN -> new ValueWrapper(
                        temporalValue.requireBoolean(),
                        ValueTypes.BOOLEAN
                );
                case NULL -> new ValueWrapper(null);
            };
        if (obj instanceof List<?> list) {
            List<ValueWrapper> wrapped = new ArrayList<>(list.size());
            for (Object element : list) wrapped.add(createValueWrapper(element));
            return new ValueWrapper(wrapped, ValueTypes.LIST);
        }
        if (obj instanceof Set<?> set) {
            Set<ValueWrapper> wrapped;
            if (set instanceof SortedSet<?>)
                wrapped = new TreeSet<>(Comparator.comparing(ValueWrapper::toNonNullString));
            else
                wrapped = new LinkedHashSet<>(Math.max(16, set.size() * 2));
            for (Object element : set) wrapped.add(createValueWrapper(element));
            return new ValueWrapper(wrapped, ValueTypes.SET);
        }
        if (obj instanceof Map<?, ?> map) {
            Map<Object, ValueWrapper> wrapped =
                    map instanceof SortedMap<?, ?>
                            ? new TreeMap<>()
                            : new LinkedHashMap<>(Math.max(16, map.size() * 2));
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                wrapped.put(entry.getKey(), createValueWrapper(entry.getValue()));
            }
            return new ValueWrapper(wrapped, ValueTypes.MAP);
        }
        if (obj instanceof ListMultimap<?, ?> multimap) {
            LinkedListMultimap<Object, ValueWrapper> wrapped = LinkedListMultimap.create();
            for (Map.Entry<?, ?> entry : multimap.entries()) {
                wrapped.put(entry.getKey(), createValueWrapper(entry.getValue()));
            }
            return new ValueWrapper(wrapped, ValueTypes.MULTIMAP);
        }
        if (obj instanceof JsonNode node) {
            if (node.isNull() || node.isMissingNode()) return new ValueWrapper(null);
            if (node.isTextual()) return new ValueWrapper(node.textValue());
            if (node.isBoolean())
                return new ValueWrapper(node.booleanValue(), ValueTypes.BOOLEAN);
            if (node.isNumber())
                return new ValueWrapper(node.decimalValue(), ValueTypes.NUMERIC);
            return new ValueWrapper(node, ValueTypes.DEFAULT, node.toString());
        }
        if (obj instanceof DataTable || obj instanceof DocString)
            return new ValueWrapper(obj, ValueTypes.DEFAULT, obj.toString());
        return new ValueWrapper(obj.toString());
    }

    private ValueWrapper(Object raw, ValueTypes type) {
        this(raw, type, canonicalNormalizedText(raw, type));
    }

    private ValueWrapper(Object raw, ValueTypes type, String normalizedText) {
        this.type = type;
        this.value = raw;
        this.normalizedText = normalizedText == null ? null : normalizeText(normalizedText);
    }

    private ValueWrapper(String raw) {
        if (raw == null) {
            this.type = ValueTypes.DEFAULT;
            this.value = null;
            this.normalizedText = null;
            return;
        }
        if (raw.length() >= 2) {
            char first = raw.charAt(0);
            char last = raw.charAt(raw.length() - 1);
            if (first == last) {
                switch (first) {
                    case '"' -> {
                        type = ValueTypes.DOUBLE_QUOTED;
                        value = unescapeMatchingQuote(
                                raw.substring(1, raw.length() - 1), first);
                        normalizedText = normalizeText((String) value);
                        return;
                    }
                    case '\'' -> {
                        type = ValueTypes.SINGLE_QUOTED;
                        value = unescapeMatchingQuote(
                                raw.substring(1, raw.length() - 1), first);
                        normalizedText = normalizeText((String) value);
                        return;
                    }
                    case '`' -> {
                        type = ValueTypes.BACK_TICKED;
                        value = unescapeMatchingQuote(
                                raw.substring(1, raw.length() - 1), first);
                        normalizedText = normalizeText((String) value);
                        return;
                    }
                    case '~' -> {
                        type = ValueTypes.TILDE_QUOTED;
                        value = unescapeMatchingQuote(
                                raw.substring(1, raw.length() - 1), first);
                        normalizedText = normalizeText((String) value);
                        return;
                    }
                }
            }
        }
        normalizedText = normalizeText(raw);
        if (isNumeric(normalizedText)) {
            type = ValueTypes.NUMERIC;
            value = normalizedText;
        } else {
            type = ValueTypes.DEFAULT;
            value = raw;
        }
    }

    private static String canonicalNormalizedText(Object raw, ValueTypes type) {
        if (raw == null) return null;
        if (type == ValueTypes.DURATION && raw instanceof Duration duration)
            return durationSyntax(duration);
        if (type == ValueTypes.DATE_TIME && raw instanceof Instant instant)
            return instant.toString();
        if (type == ValueTypes.TIME_RANGE && raw instanceof BusinessTimeRange timeRange)
            return timeRange.toCanonicalString();
        return raw.toString();
    }

    private static String durationSyntax(Duration duration) {
        return DurationFormattingUtils.format(duration, null);
    }

    private static String unescapeMatchingQuote(String value, char quote) {
        return value.replace("\\" + quote, String.valueOf(quote));
    }

    public BigInteger asForcedSimpleNumber() {
        String text = asNormalizedText();
        if (text == null) return BigInteger.ZERO;
        text = text.replaceAll("\\D", "");
        if (text.isEmpty()) return BigInteger.ZERO;
        return new BigInteger(text);
    }

    public BigDecimal asBigDecimal() {
        String text = asNormalizedText();
        if (text == null || text.isBlank()) return BigDecimal.ZERO;
        if (isNumeric()) return new BigDecimal(text);

        StringBuilder cleaned = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isDigit(character) || character == '.') {
                cleaned.append(character);
            } else if ((character == '+' || character == '-') && cleaned.isEmpty()) {
                cleaned.append(character);
            }
        }

        text = cleaned.toString();
        int lastDot = text.lastIndexOf('.');
        if (lastDot >= 0) {
            String before = text.substring(0, lastDot).replace(".", "");
            String after = text.substring(lastDot + 1).replace(".", "");
            text = before + "." + after;
        }
        boolean hasDigit = text.chars().anyMatch(Character::isDigit);
        if (!hasDigit
                || text.equals("+")
                || text.equals("-")
                || text.equals(".")
                || text.equals("+.")
                || text.equals("-.")) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(text);
    }

    public static BigInteger convertToInteger(String inputString) {
        if (inputString == null || inputString.isBlank()) return BigInteger.ZERO;

        String text = inputString.trim();
        boolean negative = text.startsWith("-");
        text = text.replaceAll("[^0-9.]", "");

        int lastDot = text.lastIndexOf('.');
        if (lastDot >= 0) {
            String before = text.substring(0, lastDot).replace(".", "");
            String after = text.substring(lastDot + 1).replace(".", "");
            text = before + after;
        }
        if (text.isEmpty()) return BigInteger.ZERO;
        if (negative) text = "-" + text;
        return new BigInteger(text);
    }

    public Integer asInteger() {
        return asBigDecimal().intValue();
    }

    public BigInteger asBigInteger() {
        return asBigDecimal().toBigInteger();
    }

    public long asLong() {
        return asBigDecimal().longValue();
    }

    public String asNormalizedText() {
        return normalizedText;
    }

    private Boolean isNumeric;

    @Override
    public String toString() {
        return value == null ? null : value.toString();
    }

    public String toNonNullString() {
        return value == null ? "" : value.toString();
    }

    private boolean isNumeric(String text) {
        if (isNumeric != null) return isNumeric;
        if (type == ValueTypes.NUMERIC) {
            isNumeric = true;
            return true;
        }
        if (text == null || text.isBlank()) {
            isNumeric = false;
            return false;
        }

        String candidate = text.strip();
        if (candidate.equals(".")
                || candidate.equals("-")
                || candidate.equals("+")
                || candidate.equals("-.")
                || candidate.equals("+.")) {
            isNumeric = false;
            return false;
        }
        if (candidate.startsWith("-") || candidate.startsWith("+"))
            candidate = candidate.substring(1);
        candidate = candidate.replaceFirst("\\.", "").replaceAll("[0-9]", "");
        isNumeric = candidate.isEmpty();
        return isNumeric;
    }

    public boolean isNumeric() {
        return isNumeric(normalizedText);
    }

    public boolean isFalsy() {
        return !isTruthy();
    }

    public boolean isTruthy() {
        if (type.toString().endsWith("QUOTED"))
            return isStringTruthy(asNormalizedText());
        if (isNumeric())
            return asForcedSimpleNumber().signum() != 0;
        return isStringTruthy(asNormalizedText());
    }

    public boolean isBlank() {
        return isNullOrBlank();
    }

    public boolean hasResolvedValue() {
        if (isNullOrBlank()) return false;
        return !(asNormalizedText().startsWith("<") && asNormalizedText().endsWith(">"));
    }

    public boolean hasValue() {
        return !(value == null || asNormalizedText().isBlank());
    }

    public boolean isNull() {
        return value == null;
    }

    public boolean isNullOrBlank() {
        return normalizedText == null || normalizedText.isBlank();
    }

    public Object asBestGuessXlsxValue() {
        if (value == null) return null;
        if (type == ValueTypes.DOUBLE_QUOTED
                || type == ValueTypes.SINGLE_QUOTED
                || type == ValueTypes.BACK_TICKED
                || type == ValueTypes.TILDE_QUOTED) {
            return value.toString();
        }

        String raw = value.toString().trim();
        if (raw.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (raw.equalsIgnoreCase("false")) return Boolean.FALSE;
        if (type == ValueTypes.BOOLEAN && value instanceof Boolean booleanValue)
            return booleanValue;

        if (type == ValueTypes.NUMERIC || looksNumeric(raw)) {
            String numericText = raw.replace("_", "");
            if (looksInteger(numericText)) {
                BigInteger integer = new BigInteger(numericText);
                return fitsInLong(integer) ? integer.longValue() : integer;
            }
            try {
                return Double.parseDouble(numericText);
            } catch (NumberFormatException ignored) {
                // Return the original value as text below.
            }
        }
        return value.toString();
    }

    private static boolean looksInteger(String text) {
        if (text == null || text.isBlank()) return false;
        int index = 0;
        if (text.charAt(0) == '-') {
            if (text.length() == 1) return false;
            index = 1;
        }
        for (; index < text.length(); index++) {
            if (!Character.isDigit(text.charAt(index))) return false;
        }
        return true;
    }

    private static boolean looksNumeric(String text) {
        if (text == null) return false;
        String candidate = text.trim();
        if (candidate.isEmpty()) return false;
        for (int i = 0; i < candidate.length(); i++) {
            char character = candidate.charAt(i);
            if (!(Character.isDigit(character)
                    || character == '-'
                    || character == '.'
                    || character == 'e'
                    || character == 'E'
                    || character == '+'
                    || character == '_')) {
                return false;
            }
        }
        return true;
    }

    private static boolean fitsInLong(BigInteger integer) {
        return integer.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                && integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0;
    }

    public ValueWrapper normalizeLowerCaseAndStripAllWhiteSpace() {
        if (normalizedText == null) return null;
        return createValueWrapper(
                "'" + normalizedText.toLowerCase().replaceAll("\\s+", "") + "'");
    }

    public ValueWrapper stripAllNonLetters() {
        if (normalizedText == null) return null;
        return createValueWrapper("~" + normalizedText + "~");
    }

    @Override
    public int hashCode() {
        if (value == null) return 0;
        if (isNumeric()) return asBigDecimal().stripTrailingZeros().hashCode();
        return normalizedText == null ? 0 : normalizedText.hashCode();
    }

    public boolean hasLetters() {
        return normalizedText != null
                && normalizedText.chars().anyMatch(Character::isLetter);
    }

    public ValueWrapper getDateTimeStringValue() {
        if (isNullOrBlank()) return null;
        if (value instanceof Instant)
            return new ValueWrapper(normalizedText, ValueTypes.DATE_TIME);
        String dateTimeString = normalizedText.trim().startsWith("DateTime:")
                ? normalizedText.trim()
                : "DateTime:" + normalizedText.trim();
        return new ValueWrapper(
                getRunningStep().resolveStepFromString(dateTimeString),
                ValueTypes.DATE_TIME
        );
    }

    public ValueWrapper getDurationStringValue() {
        if (isNullOrBlank()) return null;
        if (value instanceof Duration)
            return new ValueWrapper(normalizedText, ValueTypes.DURATION);
        String durationString = normalizedText.trim().startsWith("Duration:")
                ? normalizedText.trim()
                : "Duration:" + normalizedText.trim();
        return new ValueWrapper(
                getRunningStep().resolveStepFromString(durationString),
                ValueTypes.DURATION
        );
    }

    public ValueWrapper getTimeRangeStringValue() {
        if (isNullOrBlank()) return null;
        if (value instanceof BusinessTimeRange)
            return new ValueWrapper(normalizedText, ValueTypes.TIME_RANGE);
        String timeRangeString = normalizedText.trim().startsWith("TimeRange:")
                ? normalizedText.trim()
                : "TimeRange:" + normalizedText.trim();
        return new ValueWrapper(
                getRunningStep().resolveStepFromString(timeRangeString),
                ValueTypes.TIME_RANGE
        );
    }
}
