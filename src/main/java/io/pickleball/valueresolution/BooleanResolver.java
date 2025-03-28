package io.pickleball.valueresolution;


import java.util.*;

import static io.pickleball.configs.Constants.sFlag2;
import static io.pickleball.datafunctions.EvalList.wrapListItems;

public class BooleanResolver {
    final static Map<String, Boolean> booleanMap = new HashMap<>();

    static {
        booleanMap.put("0", false);
        booleanMap.put("", false);
        booleanMap.put("FALSE", false);
        booleanMap.put("NO", false);
        booleanMap.put("NULL", false);
        booleanMap.put("TRUE", true);
        booleanMap.put("YES", true);
    }


    public static boolean resolveObjectToBoolean(Object value) {
        if (value == null) {
            return false;
        }


        if (!(value instanceof String)) {


            if (value instanceof ValueChecker)
                return ((ValueChecker) value).getBoolValue();

            if (value.getClass().isArray())
                return resolveObjectToBoolean(wrapListItems(Arrays.asList(value)));

            if (value instanceof List<?>) {
                return resolveObjectToBoolean(wrapListItems((List) value));
            }

            // Handle collections
            if (value instanceof Collection<?>) {
                Collection<?> collection = (Collection<?>) value;
                return !collection.isEmpty() &&
                        collection.stream().anyMatch(item -> resolveObjectToBoolean(item));
            }

            // Handle arrays
//            if (value.getClass().isArray()) {
//                Object[] array = (Object[]) value;
//                return array.length > 0 &&
//                        java.util.Arrays.stream(array).anyMatch(item -> resolveObjectToBoolean(item));
//            }

            // Handle maps
            if (value instanceof Map<?, ?>) {
                Map<?, ?> map = (Map<?, ?>) value;
                return !map.isEmpty() &&
                        map.values().stream().anyMatch(item -> resolveObjectToBoolean(item));
            }

            if (value instanceof Boolean) {
                return (boolean) value;
            }

            if (value instanceof Number) {
                double numValue = ((Number) value).doubleValue();
                return numValue != 0;
            }
        }


        String strValue = value.toString().split(sFlag2, 2)[0].replaceAll("`'\"", "").trim().toUpperCase();
        if (strValue.isEmpty()) {
            return false;
        }

        // Handle string representations of empty collections
//        if (strValue.matches("\\[\\s*\\]|\\{\\s*\\}|\\(\\s*\\)")) {
        if (strValue.replaceAll("[(){}\\[\\]]", "").isBlank()) {
            return false;
        }

        // First try parsing as a number
        try {
            double numValue = Double.parseDouble(strValue);
            return numValue != 0;
        } catch (NumberFormatException e) {
            return booleanMap.getOrDefault(strValue.trim(), true);
        }
    }

}