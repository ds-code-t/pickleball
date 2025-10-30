package io.cucumber.core.runner;

import java.util.Map;

/**
 * Aspect: intercepts get*\/is*() on selected types and, if an override for the
 * bean-style property exists in OverrideSupport, returns that instead.
 *
 * No introduced interfaces, no casts in user code, no ITD methods—so javac is happy.
 */
public privileged aspect GetterOverrideSimple {

    // Scope: limit interception to your targets to avoid accidental matches.
    // Add/remove packages/classes as needed.
    pointcut inTargets():
            within(io.cucumber.core.runner..*)
                    || within(io.cucumber.core.runtime..*)
                    || within(io.cucumber.core.gherkin.messages..*)
                    || within(io.cucumber.messages.types..*)
                    || within(io.cucumber.java..*)
                    || within(tools.dscode..*);

    // Match any JavaBean-style getter on those targets (exclude getClass()).
    pointcut anyGetter(Object t):
            inTargets()
                    && target(t)
                    && (execution(* *.get*()) || execution(* *.is*()))
                    && !execution(* java.lang.Object.getClass(..));

    Object around(Object t): anyGetter(t) {
        // Derive property name from method (getLine -> "line", isActive -> "active").
        String m = thisJoinPointStaticPart.getSignature().getName();
        String prop = m.startsWith("get") ? decap(m.substring(3))
                : m.startsWith("is") ? decap(m.substring(2))
                : m;

        Map<String, Object> overrides = OverrideSupport.getMap(t);
        if (overrides.containsKey(prop)) {
            // Trust caller to store the correct boxed type (e.g., Integer for int).
            return overrides.get(prop);
        }
        return proceed(t);
    }

    private static String decap(String s) {
        return (s == null || s.isEmpty()) ? s
                : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
