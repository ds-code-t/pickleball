package io.pickleball.cacheandstate;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MethodCache {
    // Global static cache using ConcurrentHashMap for thread safety
    private static final ConcurrentMap<MethodKey, Method> cache = new ConcurrentHashMap<>();

    // Method to retrieve or cache a Method object
    public static Method getMethod(Class<?> clazz, String methodName, Class<?>[] paramTypes) {
        MethodKey key = new MethodKey(clazz, methodName, paramTypes);
        return cache.computeIfAbsent(key, k -> {
            try {
                return clazz.getMethod(methodName, paramTypes);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Method not found: " + methodName, e);
            }
        });
    }

    // Record to represent the cache key
    private record MethodKey(Class<?> clazz, String methodName, Class<?>[] paramTypes) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MethodKey other)) return false;
            return clazz == other.clazz &&
                    methodName.equals(other.methodName) &&
                    Arrays.equals(paramTypes, other.paramTypes);
        }

        @Override
        public int hashCode() {
            int result = clazz.hashCode();
            result = 31 * result + methodName.hashCode();
            result = 31 * result + Arrays.hashCode(paramTypes);
            return result;
        }
    }
}