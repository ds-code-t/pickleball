package io.pickleball.datafunctions;

import io.pickleball.valueresolution.ValueChecker;

import java.math.BigDecimal;

import static io.pickleball.stringutilities.QuoteExtracter.QUOTED_STRING_REGEX;
import static io.pickleball.valueresolution.BooleanResolver.resolveObjectToBoolean;


public class ValWrapper implements ValueChecker {
    private final String stringVal;
    private final Object initialVal;
    private final Class<?> originalType;
    private final String stringType;

    private ValWrapper(Object obj) {

        if (obj instanceof ValWrapper) {
            initialVal = ((ValWrapper) obj).initialVal;
        } else {
            initialVal = obj;
        }



        String value = "";
        if (initialVal == null) {
            this.originalType = null;
        } else {
            value = String.valueOf(initialVal);
            this.originalType = initialVal.getClass();
        }


        if (QUOTED_STRING_REGEX.matcher(value).matches()) {
            this.stringVal = value.substring(1, value.length() - 1);
            this.stringType = value.substring(0, 1);
        } else {
            this.stringVal = value;
            this.stringType = "";
        }
    }

    public String getString() {
        return stringVal;
    }

    public String getNormalized() {
        if (stringVal == null) {
            return "";
        }
        // Convert all whitespace (including newlines) to single space
        String normalized = stringVal.replaceAll("\\s+", " ");
        // Trim leading and trailing spaces
        return normalized.trim();
    }

    public int getIntValue() {
        if (stringVal == null || stringVal.isEmpty()) {
            return 0;
        }
        // Remove all non-numeric characters except minus sign
        String numericStr = stringVal.replaceAll("[^0-9-]", "");
        if (numericStr.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(numericStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public BigDecimal getBigDecimalValue() {
        if (stringVal == null || stringVal.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // First clean the string
        String cleaned = stringVal;
        // Remove periods not surrounded by numbers
        cleaned = cleaned.replaceAll("[^.0-9]", ""); // Keep only numbers and periods
        // Keep only first period
        int firstPeriodIndex = cleaned.indexOf(".");
        if (firstPeriodIndex != -1) {
            String beforePeriod = cleaned.substring(0, firstPeriodIndex + 1);
            String afterPeriod = cleaned.substring(firstPeriodIndex + 1).replace(".", "");
            cleaned = beforePeriod + afterPeriod;
        }

        if (cleaned.isEmpty()) {
            return BigDecimal.ZERO;
        }

        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    @Override
    public boolean getBoolValue() {
        if (stringVal == null) {
            return false;
        }
        return resolveObjectToBoolean(getNormalized());
    }

    public boolean hasValue() {
        if (stringVal == null) {
            return false;
        }
        String normalized = getNormalized();
        return switch (normalized) {
            case "", "null" -> false;
            default -> true;
        };
    }

    public Class<?> getOriginalType() {
        return originalType;
    }

    @Override
    public String toString() {
        return String.valueOf(initialVal);
//        return "'aazzw'";
//        System.out.println("@@initialVal: " + initialVal);
//        return "\"" + String.valueOf(initialVal) + "\"";
    }

    public static ValWrapper wrapVal(Object obj)
    {
        if (obj instanceof ValWrapper)
            return (ValWrapper) obj;
        else
            return new ValWrapper(obj);
    }
}