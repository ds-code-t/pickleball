package tools.dscode.testengine;

import java.util.List;

/** Test access to package-private bootstrap helpers. */
public final class DynamicSuiteBootstrapAccess {
    private DynamicSuiteBootstrapAccess() {
    }

    public static List<String> effective(String javaClassPath, ClassLoader context) {
        return DynamicSuiteBootstrap.effectiveClasspathEntries(javaClassPath, context);
    }
}
