// src/main/aspectj/io/cucumber/java/JavaBackend_LoadGlue_Mutator.aj
package io.cucumber.java;

import com.ctc.wstx.shaded.msv_core.util.Uri;
import tools.dscode.common.util.DebugUtils;
import tools.dscode.pickleruntime.CucumberOptionResolver;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import static tools.dscode.common.util.DebugUtils.printDebug;
import static tools.dscode.pickleruntime.CucumberOptionResolver.glueDistinct;

public aspect JavaBackend_LoadGlue_Mutator {

    private static final java.net.URI CORE_DEFS_URI =
            java.net.URI.create("classpath:/tools/dscode/coredefinitions");
//    private final LifecycleManager lifecycle = new LifecycleManager();

    // Loosened signature: match any void loadGlue(..) on JavaBackend and bind the two args
    void around(io.cucumber.core.backend.Glue glue, java.util.List gluePaths):
            execution(void io.cucumber.java.JavaBackend.loadGlue(..)) &&
                    args(glue, gluePaths) {

        List<URI> modified = new java.util.ArrayList<>();
        List<String> globalPaths =  glueDistinct();

//        lifecycle.fire(Phase.BEFORE_CUCUMBER_RUN);
//        modified.remove("classpath:");
//        modified.remove("classpath:/");
        for (Object o : globalPaths) {

            java.net.URI uri = null;
            try {
                uri = CucumberOptionResolver.toClasspathUriStrict((String) o);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
            String text = uri.toString();
            printDebug("##Glue path: " + text);
            if (text.startsWith("classpath:/tools/dscode/coredefinitions")) continue;
            if (text.equals("classpath:") || text.equals("classpath:/")) continue;

            modified.add(uri);
        }

        if (!modified.contains(CORE_DEFS_URI)) {
            modified.add(CORE_DEFS_URI);
        }

        for (Object o : gluePaths) {
            java.net.URI uri = (java.net.URI) o;
            if (modified.contains(uri)) continue;
            modified.add(uri);
        }
        try {
            modified.remove(new URI("classpath:/"));
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        DebugUtils.onMatch("##Glue final-URI: ", msg -> {
            for(URI uri : modified) System.out.println(msg + uri );;
        });

        proceed(glue, modified);
    }
}
