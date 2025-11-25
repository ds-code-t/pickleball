// src/main/aspectj/io/cucumber/java/JavaBackend_LoadGlue_Mutator.aj
package io.cucumber.java;

import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.annotations.Phase;
import tools.dscode.pickleruntime.CucumberOptionResolver;

import java.net.URI;
import java.net.URISyntaxException;

import static tools.dscode.common.util.DebugUtils.printDebug;
import static tools.dscode.pickleruntime.CucumberOptionResolver.glueDistinct;

public aspect JavaBackend_LoadGlue_Mutator {

    private static final java.net.URI CORE_DEFS_URI =
            java.net.URI.create("classpath:/tools/dscode/coredefinitions");
    private final LifecycleManager lifecycle = new LifecycleManager();

    // Loosened signature: match any void loadGlue(..) on JavaBackend and bind the two args
    void around(io.cucumber.core.backend.Glue glue, java.util.List gluePaths):
            execution(void io.cucumber.java.JavaBackend.loadGlue(..)) &&
                    args(glue, gluePaths) {

        java.util.List modified = new java.util.ArrayList();
        java.util.List globalPaths =  glueDistinct();
        printDebug("@@old gluePaths: " + gluePaths);
        lifecycle.fire(Phase.BEFORE_CUCUMBER_RUN);
//        modified.remove("classpath:");
//        modified.remove("classpath:/");
        for (Object o : globalPaths) {
            printDebug("@@gluePath1: " + o + "");
            java.net.URI uri = null;
            try {
                uri = CucumberOptionResolver.toClasspathUriStrict((String) o);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
            String text = uri.toString();

            if (text.startsWith("classpath:/tools/dscode/coredefinitions")) continue;
            if (text.equals("classpath:") || text.equals("classpath:/")) continue;
            printDebug("@@gluePath11: " + uri + "");
            modified.add(uri);
        }

        if (!modified.contains(CORE_DEFS_URI)) {
            modified.add(CORE_DEFS_URI);
        }

        for (Object o : gluePaths) {
            printDebug("@@gluePath2: " + o + "");
            java.net.URI uri = (java.net.URI) o;
            if (modified.contains(uri)) continue;
            modified.add(uri);
        }
        try {
            modified.remove(new URI("classpath:/"));
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        printDebug("@@modified gluePaths: " + modified);
        proceed(glue, modified);
    }
}
