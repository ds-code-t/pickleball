package tools.dscode.common.treeparsing.parsedComponents;

import java.math.BigInteger;
import java.util.Objects;

public class ValueWrapper {

    public final ValueTypes type;
    public final String value;

    public enum ValueTypes {
        DOUBLE_QUOTED, SINGLE_QUOTED, BACK_TICKED, NUMERIC, OTHER
    }

    public boolean isNull() { return value == null; }
    public boolean isBlank() { return value.isBlank(); }
    public boolean isNullOrBlank() { return value == null || value.isBlank(); }

    public ValueWrapper(String raw) {
        Objects.requireNonNull(raw);

        String s = raw.trim();
        if (s.length() >= 2) {
            char f = s.charAt(0);
            char l = s.charAt(s.length() - 1);
            if (f == l) {
                switch (f) {
                    case '"'  -> { type = ValueTypes.DOUBLE_QUOTED; value = s.substring(1, s.length() - 1); return; }
                    case '\'' -> { type = ValueTypes.SINGLE_QUOTED; value = s.substring(1, s.length() - 1); return; }
                    case '`'  -> { type = ValueTypes.BACK_TICKED;   value = s.substring(1, s.length() - 1); return; }
                }
            }
        }

        value = s;
        type = isNumeric(s) ? ValueTypes.NUMERIC : ValueTypes.OTHER;
    }

    public BigInteger asBigInteger() {
        if (type == ValueTypes.NUMERIC) {
            String s = trimmedValue();
            if (s.contains(".")) s = s.replaceFirst("\\.", "").replace(".", "");
            return new BigInteger(s);
        }

        String s = trimmedValue()
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


    public String trimmedValue() {
        return value.trim();
    }

    private static boolean isNumeric(String s) {
        String t = s.trim();
        if (t.startsWith("-")) t = t.substring(1);
        int dot = t.indexOf('.');
        if (dot >= 0) t = t.substring(0, dot) + t.substring(dot + 1);
        if (t.isEmpty()) return false;
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return value;
    }
}
