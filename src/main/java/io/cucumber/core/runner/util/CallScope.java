// src/main/java/tools/ds/util/CallScope.java
package io.cucumber.core.runner.util;

public final class CallScope {
    private static final ThreadLocal<Object> CURRENT_THIS = new ThreadLocal<>();
    private CallScope() {
    }

    public static void setSelf(Object self) {
        CURRENT_THIS.set(self);
    }

    public static Object currentSelf() {
        return CURRENT_THIS.get();
    }

    public static void clear() {
        CURRENT_THIS.remove();
    }
}
