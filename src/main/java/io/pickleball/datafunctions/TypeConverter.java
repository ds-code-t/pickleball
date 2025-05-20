package io.pickleball.datafunctions;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public interface TypeConverter {
    Object get(Object value);

    default List<Object> getNonNullValues(Object... values) {
        List<Object> results = new ArrayList<>();
        for (Object value : values) {
            Object result = get(value);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    default Object getFirstNonNull(Object... values) {
        for (Object value : values) {
            Object result = get(value);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    default String getFirstString(Object... values) {
        for (Object result : getNonNullValues(values)) {
            try {
                return toString(result);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    default Integer getFirstInt(Object... values) {
        for (Object result : getNonNullValues(values)) {
            try {
                return toInt(result);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    default Long getFirstLong(Object... values) {
        for (Object result : getNonNullValues(values)) {
            try {
                return toLong(result);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    default Double getFirstDouble(Object... values) {
        for (Object result : getNonNullValues(values)) {
            try {
                return toDouble(result);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    default Float getFirstFloat(Object... values) {
        for (Object result : getNonNullValues(values)) {
            try {
                return toFloat(result);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    default Short getFirstShort(Object... values) {
        for (Object result : getNonNullValues(values)) {
            try {
                return toShort(result);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    default Byte getFirstByte(Object... values) {
        for (Object result : getNonNullValues(values)) {
            try {
                return toByte(result);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    default BigDecimal getFirstBigDecimal(Object... values) {
        for (Object result : getNonNullValues(values)) {
            try {
                return toBigDecimal(result);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    default BigInteger getFirstBigInteger(Object... values) {
        for (Object result : getNonNullValues(values)) {
            try {
                return toBigInteger(result);
            } catch (Exception ignored) {
            }
        }
        return null;
    }


    default String getAsString(Object value) {
        try {
            Object result = get(value);
            return toString(result);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format("Failed to convert input=%s, get() result=%s to String: %s - %s",
                            String.valueOf(value), String.valueOf(get(value)), e.getClass().getSimpleName(), e.getMessage()), e);
        }
    }

    default Integer getAsInt(Object value) {
        try {
            Object result = get(value);
            return toInt(result);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format("Failed to convert input=%s, get() result=%s to Integer: %s - %s",
                            String.valueOf(value), String.valueOf(get(value)), e.getClass().getSimpleName(), e.getMessage()), e);
        }
    }

    default Long getAsLong(Object value) {
        try {
            Object result = get(value);
            return toLong(result);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format("Failed to convert input=%s, get() result=%s to Long: %s - %s",
                            String.valueOf(value), String.valueOf(get(value)), e.getClass().getSimpleName(), e.getMessage()), e);
        }
    }

    default Double getAsDouble(Object value) {
        try {
            Object result = get(value);
            return toDouble(result);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format("Failed to convert input=%s, get() result=%s to Double: %s - %s",
                            String.valueOf(value), String.valueOf(get(value)), e.getClass().getSimpleName(), e.getMessage()), e);
        }
    }

    default Float getAsFloat(Object value) {
        try {
            Object result = get(value);
            return toFloat(result);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format("Failed to convert input=%s, get() result=%s to Float: %s - %s",
                            String.valueOf(value), String.valueOf(get(value)), e.getClass().getSimpleName(), e.getMessage()), e);
        }
    }

    default Short getAsShort(Object value) {
        try {
            Object result = get(value);
            return toShort(result);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format("Failed to convert input=%s, get() result=%s to Short: %s - %s",
                            String.valueOf(value), String.valueOf(get(value)), e.getClass().getSimpleName(), e.getMessage()), e);
        }
    }

    default Byte getAsByte(Object value) {
        try {
            Object result = get(value);
            return toByte(result);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format("Failed to convert input=%s, get() result=%s to Byte: %s - %s",
                            String.valueOf(value), String.valueOf(get(value)), e.getClass().getSimpleName(), e.getMessage()), e);
        }
    }

    default BigDecimal getAsBigDecimal(Object value) {
        try {
            Object result = get(value);
            return toBigDecimal(result);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format("Failed to convert input=%s, get() result=%s to BigDecimal: %s - %s",
                            String.valueOf(value), String.valueOf(get(value)), e.getClass().getSimpleName(), e.getMessage()), e);
        }
    }

    default BigInteger getAsBigInteger(Object value) {
        try {
            Object result = get(value);
            return toBigInteger(result);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    String.format("Failed to convert input=%s, get() result=%s to BigInteger: %s - %s",
                            String.valueOf(value), String.valueOf(get(value)), e.getClass().getSimpleName(), e.getMessage()), e);
        }
    }

    static String toString(Object value) {
        return String.valueOf(value);
    }

    static Integer toInt(Object value) {
        return new BigDecimal(String.valueOf(value)).intValue();
    }

    static Long toLong(Object value) {
        return new BigDecimal(String.valueOf(value)).longValue();
    }

    static Double toDouble(Object value) {
        return new BigDecimal(String.valueOf(value)).doubleValue();
    }

    static Float toFloat(Object value) {
        return new BigDecimal(String.valueOf(value)).floatValue();
    }

    static Short toShort(Object value) {
        return new BigDecimal(String.valueOf(value)).shortValue();
    }

    static Byte toByte(Object value) {
        return new BigDecimal(String.valueOf(value)).byteValue();
    }

    static BigDecimal toBigDecimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    static BigInteger toBigInteger(Object value) {
        return new BigDecimal(String.valueOf(value)).toBigInteger();
    }
}