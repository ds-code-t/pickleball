// src/main/java/tools/ds/modkit/util/ExecutionModes.java
package tools.ds.modkit.util;

public final class ExecutionModes {
    private static final String FQCN = "io.cucumber.core.runner.ExecutionMode";

    private ExecutionModes() {}

    /* ------------------ getters (return the actual enum instance as Object) ------------------ */

    public static Object RUN(Object anchor)     { return constant(loaderFor(anchor), "RUN"); }
    public static Object DRY_RUN(Object anchor) { return constant(loaderFor(anchor), "DRY_RUN"); }
    public static Object SKIP(Object anchor)    { return constant(loaderFor(anchor), "SKIP"); }

    // Convenience when you don’t have a good anchor (uses TCCL, then our loader)
    public static Object RUN()     { return constant(loaderFor(null), "RUN"); }
    public static Object DRY_RUN() { return constant(loaderFor(null), "DRY_RUN"); }
    public static Object SKIP()    { return constant(loaderFor(null), "SKIP"); }

    /* ------------------ predicates (test an Object for specific mode) ------------------ */

    public static boolean isRun(Object mode)     { return is(mode, "RUN"); }
    public static boolean isDryRun(Object mode)  { return is(mode, "DRY_RUN"); }
    public static boolean isSkip(Object mode)    { return is(mode, "SKIP"); }

    /* -------------------------------- internals -------------------------------- */

    private static boolean is(Object mode, String name) {
        if (mode == null) return false;
        Class<?> c = mode.getClass();
        if (!FQCN.equals(c.getName())) return false; // wrong type
        Object constant = enumValue(c, name);
        return mode == constant; // same classloader => identity works
    }

    private static Object constant(ClassLoader cl, String name) {
        Class<?> enumClass = loadEnumClass(cl);
        return enumValue(enumClass, name);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object enumValue(Class<?> enumClass, String name) {
        try {
            return Enum.valueOf((Class) enumClass, name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("No enum constant " + enumClass.getName() + "." + name, e);
        }
    }

    private static Class<?> loadEnumClass(ClassLoader cl) {
        try {
            return Class.forName(FQCN, false, cl);
        } catch (ClassNotFoundException e) {
            // last-ditch: try our own loader
            try {
                return Class.forName(FQCN, false, ExecutionModes.class.getClassLoader());
            } catch (ClassNotFoundException e2) {
                throw new IllegalStateException("Cannot load " + FQCN + " with class loader(s)", e2);
            }
        }
    }

    private static ClassLoader loaderFor(Object anchor) {
        if (anchor != null) {
            ClassLoader fromAnchor = (anchor instanceof Class<?>)
                    ? ((Class<?>) anchor).getClassLoader()
                    : anchor.getClass().getClassLoader();
            if (fromAnchor != null) return fromAnchor;
        }
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        return (tccl != null) ? tccl : ExecutionModes.class.getClassLoader();
    }
}
