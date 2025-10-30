// src/main/aspectj/io/cucumber/java/JavaBackend_LoadGlue_Mutator.aj
package io.cucumber.java;

public aspect JavaBackend_LoadGlue_Mutator {

    private static final java.net.URI CORE_DEFS_URI =
            java.net.URI.create("classpath:/tools/dscode/coredefinitions");

    // Loosened signature: match any void loadGlue(..) on JavaBackend and bind the two args
    void around(io.cucumber.core.backend.Glue glue, java.util.List gluePaths):
            execution(void io.cucumber.java.JavaBackend.loadGlue(..)) &&
                    args(glue, gluePaths) {

        java.util.List modified = new java.util.ArrayList();
        System.out.println("@@old gluePaths: " + gluePaths);

        for (Object o : gluePaths) {
            java.net.URI uri = (java.net.URI) o;
            String text = uri.toString();

            if (text.startsWith("classpath:/tools/dscode/coredefinitions")) continue;
            if (text.equals("classpath:") || text.equals("classpath:/")) continue;

            modified.add(uri);
        }

        if (!modified.contains(CORE_DEFS_URI)) {
            modified.add(CORE_DEFS_URI);
        }

        System.out.println("@@modified gluePaths: " + modified);
        proceed(glue, modified);
    }
}
