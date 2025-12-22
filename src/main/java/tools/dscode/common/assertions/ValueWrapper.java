package tools.dscode.common.assertions;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.time.Duration;

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
        if(value.equals(o))
            return true;
        if(isNumeric || o instanceof Number)
        {
            return asBigInteger().equals(convertToInteger(normalizeText(o.toString())));
        }
        String normalizedInput = normalizeText(o.toString());
        return (value.equals(o) || normalizedText.equals(normalizedInput));

    }

    public Object getValue() {
        return value;
    }

    public enum ValueTypes {
        DOUBLE_QUOTED, SINGLE_QUOTED, BACK_TICKED, NUMERIC, DEFAULT, BOOLEAN
    }



    public static ValueWrapper createValueWrapper(Object obj) {
        if (obj == null || obj instanceof String)
            return new ValueWrapper((String) obj);
        if (obj instanceof Number)
            return new ValueWrapper(obj, ValueTypes.NUMERIC);
        if (obj instanceof JsonNode node) {
            if (node == null || node.isNull() || node.isMissingNode()) return new ValueWrapper(null);

            if (node.isTextual()) return new ValueWrapper(node.textValue());
            if (node.isBoolean()) new ValueWrapper(node.booleanValue(), ValueTypes.BOOLEAN);

            if (node.isNumber()) {
                return new ValueWrapper(node.bigIntegerValue(), ValueTypes.NUMERIC);
            }
            return new ValueWrapper(node.textValue());
        }
        return new ValueWrapper(obj.toString());
    }


    private ValueWrapper(Object raw, ValueTypes type) {
        this.type = type;
        this.value = raw;
        this.normalizedText = raw == null ? null : normalizeText(raw.toString());
    }


    private ValueWrapper(String raw) {
        if (raw == null) {
            this.type = ValueTypes.DEFAULT;
            this.value = null;
            this.normalizedText = null;
            return;
        }


//        String s = raw.trim();
        if (raw.length() >= 2) {
            char f = raw.charAt(0);
            char l = raw.charAt(raw.length() - 1);
            if (f == l) {
                switch (f) {
                    case '"' -> {
                        type = ValueTypes.DOUBLE_QUOTED;
                        value = raw.substring(1, raw.length() - 1);
                        this.normalizedText = normalizeText((String) value);
                        return;
                    }
                    case '\'' -> {
                        type = ValueTypes.SINGLE_QUOTED;
                        value = raw.substring(1, raw.length() - 1);
                        this.normalizedText = normalizeText((String) value);
                        return;
                    }
                    case '`' -> {
                        type = ValueTypes.BACK_TICKED;
                        value = raw.substring(1, raw.length() - 1);
                        this.normalizedText = normalizeText((String) value);
                        return;
                    }
                }
            }
        }
        this.normalizedText = normalizeText(raw);
        if (isNumeric(normalizedText)) {
            type = ValueTypes.NUMERIC;
            value = normalizedText;
        } else {
            type = ValueTypes.DEFAULT;
            value = raw;
        }
    }

    private BigInteger bigIntegerValue;

    public BigInteger asBigInteger() {
        if (bigIntegerValue != null) return bigIntegerValue;

        if (type == ValueTypes.NUMERIC) {
            String s = asNormalizedText();
            if (s.contains(".")) s = s.replaceFirst("\\.", "").replace(".", "");
            bigIntegerValue = new BigInteger(s);
            return bigIntegerValue;
        }
        bigIntegerValue = convertToInteger(normalizedText);
        return bigIntegerValue;
    }


    public static BigInteger convertToInteger(String inputString) {
        String s = inputString
                .replaceAll("[^0-9.\\-]", " ")
                .replaceAll("\\.(?=.*\\.)", "");

        String[] parts = s.trim().split("\\s+");
        String last = parts.length == 0 ? "" : parts[parts.length - 1];

        if (last.isEmpty() || last.equals(".") || last.equals("-")) return BigInteger.ZERO;

        return new BigInteger(last.replace(".", ""));
    }

    public Integer asInteger() {
        return asBigInteger().intValueExact();
    }


    public String asNormalizedText() {
        return normalizedText;
    }

    private Boolean isNumeric;




    public Duration asDuration(String unit) {
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("Time unit must not be null or blank");
        }

        BigInteger value = asBigInteger();
        String u = unit.trim().toLowerCase();

        return switch (u) {
            // seconds
            case "s", "sec", "secs", "second", "seconds" -> Duration.ofSeconds(value.longValueExact());

            // minutes
            case "m", "min", "mins", "minute", "minutes" -> Duration.ofMinutes(value.longValueExact());

            // hours
            case "h", "hr", "hrs", "hour", "hours" -> Duration.ofHours(value.longValueExact());

            // days (often useful)
            case "d", "day", "days" -> Duration.ofDays(value.longValueExact());

            // milliseconds
            case "ms", "millis", "millisecond", "milliseconds" -> Duration.ofMillis(value.longValueExact());

            default -> throw new IllegalArgumentException("Unsupported time unit: " + unit);
        };
    }


    @Override
    public String toString() {
        if (value == null) return null;
        return value.toString();
    }


    public String toNonNullString() {
        if (value == null) return "";
        return value.toString();
    }


    private boolean isNumeric(String s) {
        if (isNumeric != null) return isNumeric;
        if (type == ValueTypes.NUMERIC) {
            isNumeric = true;
            return isNumeric;
        }
        String t = s.trim();
        if (t.startsWith("-")) t = t.substring(1);
        int dot = t.indexOf('.');
        if (dot >= 0) t = t.substring(0, dot) + t.substring(dot + 1);
        if (t.isEmpty()) return false;
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) {
                isNumeric = false;
                return isNumeric;
            }
        }
        isNumeric = true;
        return isNumeric;
    }


    public boolean isTruthy() {
        if (isNumeric) {
            return asBigInteger().signum() != 0;
        }
        return isStringTruthy(asNormalizedText());
    }

    public boolean isBlank() {
        return (value == null || asNormalizedText().isBlank());
    }
    public boolean hasValue() {
        return !(value == null || asNormalizedText().isBlank());
    }

    public boolean isNull() {
        return value == null;
    }

    public boolean isNullOrBlank() {
        return value == null || value.toString().isBlank();
    }


}
