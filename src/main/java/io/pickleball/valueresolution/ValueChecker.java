package io.pickleball.valueresolution;

import static io.pickleball.valueresolution.BooleanResolver.resolveObjectToBoolean;

public interface ValueChecker {
    default boolean getBoolValue() {
        return resolveObjectToBoolean(String.valueOf(this));
    }

//    default boolean hasValue() {
//        String val = String.valueOf(this).strip().toLowerCase();
//        return (!val.isEmpty() && !val.equalsIgnoreCase("null"));
//    }
}
