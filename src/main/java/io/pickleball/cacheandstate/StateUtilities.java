package io.pickleball.cacheandstate;


import java.math.BigDecimal;
import java.math.BigInteger;

import static io.pickleball.cacheandstate.ScenarioContext.getRunMaps;

import java.util.Arrays;
import java.util.List;

public class StateUtilities {

    public static List<Object> getNonNullValues(Object... values) {
        return getRunMaps().getNonNullValues(values);
    }

    public static Object getFirstNonNull(Object... values) {
        return getRunMaps().getFirstNonNull(values);
    }



    record VarargsResult(Object[] valuesWithoutDefault, Object defaultValue) {}

    private static VarargsResult parseVarargs(Object first, Object second, Object... rest) {
        Object[] values = new Object[2 + rest.length];
        values[0] = first;
        values[1] = second;
        System.arraycopy(rest, 0, values, 2, rest.length);
        Object defaultValue = values[values.length - 1];
        Object[] valuesWithoutDefault = new Object[values.length - 1];
        System.arraycopy(values, 0, valuesWithoutDefault, 0, valuesWithoutDefault.length);
        return new VarargsResult(valuesWithoutDefault, defaultValue);
    }




    public static String getOrDefaultString(Object first, Object second, Object... rest) {
        VarargsResult parsed = parseVarargs(first, second, rest);
        String result = getRunMaps().getFirstString(parsed.valuesWithoutDefault());
        System.out.println("@@parsed.valuesWithoutDefault(): " + Arrays.asList(parsed.valuesWithoutDefault()));
        System.out.println("@@result: " + result);
        System.out.println("@@parsed.defaultValue(): " + parsed.defaultValue());

        return result != null ? result : toString(parsed.defaultValue());
    }

    public static Integer getOrDefaultInt(Object first, Object second, Object... rest) {
        VarargsResult parsed = parseVarargs(first, second, rest);
        Integer result = getRunMaps().getFirstInt(parsed.valuesWithoutDefault());
        return result != null ? result : toInt(parsed.defaultValue());
    }

    public static Long getOrDefaultLong(Object first, Object second, Object... rest) {
        VarargsResult parsed = parseVarargs(first, second, rest);
        Long result = getRunMaps().getFirstLong(parsed.valuesWithoutDefault());
        return result != null ? result : toLong(parsed.defaultValue());
    }

    public static Double getOrDefaultDouble(Object first, Object second, Object... rest) {
        VarargsResult parsed = parseVarargs(first, second, rest);
        Double result = getRunMaps().getFirstDouble(parsed.valuesWithoutDefault());
        return result != null ? result : toDouble(parsed.defaultValue());
    }

    public static Float getOrDefaultFloat(Object first, Object second, Object... rest) {
        VarargsResult parsed = parseVarargs(first, second, rest);
        Float result = getRunMaps().getFirstFloat(parsed.valuesWithoutDefault());
        return result != null ? result : toFloat(parsed.defaultValue());
    }

    public static Short getOrDefaultShort(Object first, Object second, Object... rest) {
        VarargsResult parsed = parseVarargs(first, second, rest);
        Short result = getRunMaps().getFirstShort(parsed.valuesWithoutDefault());
        return result != null ? result : toShort(parsed.defaultValue());
    }

    public static Byte getOrDefaultByte(Object first, Object second, Object... rest) {
        VarargsResult parsed = parseVarargs(first, second, rest);
        Byte result = getRunMaps().getFirstByte(parsed.valuesWithoutDefault());
        return result != null ? result : toByte(parsed.defaultValue());
    }

    public static BigDecimal getOrDefaultBigDecimal(Object first, Object second, Object... rest) {
        VarargsResult parsed = parseVarargs(first, second, rest);
        BigDecimal result = getRunMaps().getFirstBigDecimal(parsed.valuesWithoutDefault());
        return result != null ? result : toBigDecimal(parsed.defaultValue());
    }

    public static BigInteger getOrDefaultBigInteger(Object first, Object second, Object... rest) {
        VarargsResult parsed = parseVarargs(first, second, rest);
        BigInteger result = getRunMaps().getFirstBigInteger(parsed.valuesWithoutDefault());
        return result != null ? result : toBigInteger(parsed.defaultValue());
    }


    public static Object getOrDefaultObject(Object first, Object second, Object... rest) {
        VarargsResult parsed = parseVarargs(first, second, rest);
        Object result = getRunMaps().getNonNullValues(parsed.valuesWithoutDefault());
        return result != null ? result : parsed.defaultValue();
    }

    public static Object getFirstObject(Object... values) {
        return getRunMaps().getNonNullValues(values);
    }

    public static String getFirstString(Object... values) {
        return getRunMaps().getFirstString(values);
    }
    public static Integer getFirstInt(Object... values) {
        return getRunMaps().getFirstInt(values);
    }

    public static Long getFirstLong(Object... values) {
        return getRunMaps().getFirstLong(values);
    }

    public static Double getFirstDouble(Object... values) {
        return getRunMaps().getFirstDouble(values);
    }

    public static Float getFirstFloat(Object... values) {
        return getRunMaps().getFirstFloat(values);
    }

    public static Short getFirstShort(Object... values) {
        return getRunMaps().getFirstShort(values);
    }

    public static Byte getFirstByte(Object... values) {
        return getRunMaps().getFirstByte(values);
    }

    public static BigDecimal getFirstBigDecimal(Object... values) {
        return getRunMaps().getFirstBigDecimal(values);
    }

    public static BigInteger getFirstBigInteger(Object... values) {
        return getRunMaps().getFirstBigInteger(values);
    }


    public static String getAsString(Object value) {
        return getRunMaps().getAsString(value);
    }

    public static Integer getAsInt(Object value) {
        return getRunMaps().getAsInt(value);
    }

    public static Long getAsLong(Object value) {
        return getRunMaps().getAsLong(value);
    }

    public static Double getAsDouble(Object value) {
        return getRunMaps().getAsDouble(value);
    }

    public static Float getAsFloat(Object value) {
        return getRunMaps().getAsFloat(value);
    }

    public static Short getAsShort(Object value) {
        return getRunMaps().getAsShort(value);
    }

    public static Byte getAsByte(Object value) {
        return getRunMaps().getAsByte(value);
    }

    public static BigDecimal getAsBigDecimal(Object value) {
        return getRunMaps().getAsBigDecimal(value);
    }

    public static BigInteger getAsBigInteger(Object value) {
        return getRunMaps().getAsBigInteger(value);
    }



    public static String toString(Object value) {
        return String.valueOf(value);
    }

    public static Integer toInt(Object value) {
        return new BigDecimal(String.valueOf(value)).intValue();
    }

    public static Long toLong(Object value) {
        return new BigDecimal(String.valueOf(value)).longValue();
    }

    public static Double toDouble(Object value) {
        return new BigDecimal(String.valueOf(value)).doubleValue();
    }

    public static Float toFloat(Object value) {
        return new BigDecimal(String.valueOf(value)).floatValue();
    }

    public static Short toShort(Object value) {
        return new BigDecimal(String.valueOf(value)).shortValue();
    }

    public static Byte toByte(Object value) {
        return new BigDecimal(String.valueOf(value)).byteValue();
    }

    public static BigDecimal toBigDecimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    public static BigInteger toBigInteger(Object value) {
        return new BigDecimal(String.valueOf(value)).toBigInteger();
    }

}