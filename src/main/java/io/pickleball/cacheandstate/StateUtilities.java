package io.pickleball.cacheandstate;


import java.math.BigDecimal;
import java.math.BigInteger;

import static io.pickleball.cacheandstate.ScenarioContext.getRunMaps;

import java.util.List;

public class StateUtilities {

    public static List<Object> getNonNullValues(Object... values) {
        return getRunMaps().getNonNullValues(values);
    }

    public static Object getFirstNonNull(Object... values) {
        return getRunMaps().getFirstNonNull(values);
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
}