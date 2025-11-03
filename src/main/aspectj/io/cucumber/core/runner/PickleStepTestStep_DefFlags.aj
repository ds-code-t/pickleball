package io.cucumber.core.runner;

import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.DefinitionFlags;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;


/**
 * Adds (and populates) definitionFlags + method on PickleStepTestStep.
 * - method: underlying Java method of the bound step definition (if present)
 * - definitionFlags: values from @DefinitionFlags on that method (if present)
 *
 * Safe defaults:
 * - method = null
 * - definitionFlags = Collections.emptyList()
 */
public privileged aspect PickleStepTestStep_DefFlags {

    /* ===== Inter-type declarations (new members) ===== */

    /** Underlying Java method for the bound step definition (or null). */
    public Method io.cucumber.core.runner.PickleStepTestStep.method = null;

    /** Flags declared on the method via @DefinitionFlags (or empty). */
    public List<DefinitionFlag> io.cucumber.core.runner.PickleStepTestStep.definitionFlags =
            java.util.Collections.emptyList();

    /* Optional convenience getters (remove if you don't want them) */
    public Method io.cucumber.core.runner.PickleStepTestStep.getMethod() {
        return this.method;
    }
    public List<DefinitionFlag> io.cucumber.core.runner.PickleStepTestStep.getDefinitionFlags() {
        return this.definitionFlags;
    }

    /* ===== Advice: run immediately after any PickleStepTestStep ctor ===== */

    /** Match all constructors of PickleStepTestStep (any signature). */
    pointcut ctor(PickleStepTestStep thiz) :
            this(thiz) && execution(io.cucumber.core.runner.PickleStepTestStep+.new(..));

    /** After construction, populate fields using the provided reflection logic. */
    after(PickleStepTestStep thiz) returning : ctor(thiz) {
        try {
            // Your original logic, adapted here:
            // getProperty(thiz, "definitionMatch.stepDefinition.stepDefinition.method")
            Object got = getProperty(thiz, "definitionMatch.stepDefinition.stepDefinition.method");

            Method m = (got instanceof Method) ? (Method) got : null;
            List<DefinitionFlag> flags;

            if (m != null) {
                DefinitionFlags ann = m.getAnnotation(DefinitionFlags.class);
                flags = (ann == null)
                        ? java.util.Collections.emptyList()
                        : Arrays.asList(ann.value());
            } else {
                flags = java.util.Collections.emptyList();
            }

            // Assign safely
            thiz.method = m;
            // Use an immutable view to avoid accidental external mutation
            thiz.definitionFlags = (flags == null || flags.isEmpty())
                    ? java.util.Collections.emptyList()
                    : java.util.List.copyOf(flags);

        } catch (Throwable t) {
            // Fail-safe defaults
            thiz.method = null;
            thiz.definitionFlags = java.util.Collections.emptyList();
        }
    }

    /* ===== Simple nested-field resolver used by the advice ===== */

    /**
     * Walks dot-separated field names reflectively, starting at root.
     * Example path used here: "definitionMatch.stepDefinition.stepDefinition.method"
     * Returns the final object (may be null) or throws on hard reflection failures.
     */
    private static Object getProperty(Object root, String path) {
        if (root == null || path == null || path.isBlank()) return null;

        Object current = root;
        for (String part : path.split("\\.")) {
            if (current == null) return null;
            Class<?> c = current.getClass();
            Field f = findField(c, part);
            if (f == null) return null; // field not found -> treat as missing
            try {
                f.setAccessible(true);
                current = f.get(current);
            } catch (IllegalAccessException e) {
                return null; // treat as missing if we can't read it
            }
        }
        return current;
    }

    /** Finds a declared field by name up the class hierarchy. */
    private static Field findField(Class<?> type, String name) {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
