package tools.dscode.aspects;

import tools.dscode.registry.GlobalRegistry;

public aspect RegisterBothByList {

    /* Include runner package so TestCase is captured */
    pointcut ctorInScope():
            execution(io.cucumber.core.runtime..*.new(..))
                    || execution(io.cucumber.core.feature..*.new(..))
                    || execution(io.cucumber.core.runner..*.new(..))    // ← added
                    || execution(io.cucumber.java..*.new(..));

    /* Allow-list by FQCN (loader-agnostic matching) */
    private static final String[] TARGET_FQCNS = new String[] {
            "io.cucumber.core.runner.Runner",
            "io.cucumber.core.runtime.Runtime",
            "io.cucumber.core.feature.FeatureParser",
            "io.cucumber.core.runtime.FeaturePathFeatureSupplier",
            "io.cucumber.core.runner.TestCase",
            "io.cucumber.core.runner.TestCaseState",
            "io.cucumber.java.JavaBackend"
    };

    private static boolean nameInAllowList(String fqcn) {
        for (String s : TARGET_FQCNS) if (s.equals(fqcn)) return true;
        return false;
    }

    private static boolean matchesByName(Object o) {
        if (o == null) return false;
        Class<?> c = o.getClass();
        while (c != null) {
            if (nameInAllowList(c.getName())) return true;
            for (Class<?> itf : c.getInterfaces()) {
                if (nameInAllowList(itf.getName())) return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    private static boolean looksAnonymousOrSynthetic(Class<?> c) {
        String n = c.getName();
        return n.contains("$$") || n.matches(".*\\$\\d+");
    }

    /* Cleaner binding: get constructed instance directly */
    after(Object obj) returning : ctorInScope() && this(obj) {
        if (obj == null) return;
        if (looksAnonymousOrSynthetic(obj.getClass())) return;
        if (!matchesByName(obj)) return;

        GlobalRegistry.registerBoth(obj);
    }
}
