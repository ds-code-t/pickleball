// src/main/aspectj/io/cucumber/java/JavaBackend_LoadGlue_Mutator.aj
package io.cucumber.java;

import io.cucumber.core.runner.Runner;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.reporting.logging.simplehtml.SimpleHtmlReportConverter;
//import tools.dscode.pickleruntime.CucumberOptionResolver;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.lifecycle;
import static io.cucumber.core.runner.GlobalState.setGlobalCachingGlue;
import static io.cucumber.core.runner.GlobalState.setGlobalRunner;
import static tools.dscode.common.reporting.logging.LogForwarder.stepDebug;
import static tools.dscode.testengine.DynamicSuiteConfigUtils.getGluePaths;
//import static tools.dscode.pickleruntime.CucumberOptionResolver.glueDistinct;

public aspect JavaBackend_LoadGlue_Mutator {

    private static final URI CORE_DEFS_URI =
            URI.create("classpath:/tools/dscode/coredefinitions");

    void around(
            Runner runner,
            io.cucumber.java.JavaBackend backend,
            io.cucumber.core.backend.Glue glue,
            java.util.List gluePaths
    ):
            call(void io.cucumber.core.backend.Backend.loadGlue(..)) &&
                    this(runner) &&
                    target(backend) &&
                    args(glue, gluePaths) {


        List<URI> modified = new ArrayList<>();
        List<String> globalPaths = glueDistinct();


//        modified.remove("classpath:");
//        modified.remove("classpath:/");
        for (Object o : globalPaths) {
            URI uri;
            try {
                uri = toClasspathUriStrict((String) o);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }

            String text = uri.toString();
            if (text.startsWith("classpath:/tools/dscode/coredefinitions")) continue;
            if (text.equals("classpath:") || text.equals("classpath:/")) continue;

            modified.add(uri);
        }

        if (!modified.contains(CORE_DEFS_URI)) {
            modified.add(CORE_DEFS_URI);
        }

        for (Object o : gluePaths) {
            URI uri = (URI) o;
            if (!modified.contains(uri)) {
                modified.add(uri);
            }
        }

        try {
            modified.remove(new URI("classpath:/"));
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        stepDebug(
                "Runner=" + runner.getClass().getName() +
                        ", backend=" + backend.getClass().getName() +
                        ", glue paths set: " +
                        modified.stream().map(URI::toString).collect(java.util.stream.Collectors.joining(","))
        );
        proceed(runner, backend, glue, modified);

        setGlobalRunner(runner);
        setGlobalCachingGlue(glue);
    }
        public static URI toClasspathUriStrict(String pkgOrPath) throws URISyntaxException {
        if (pkgOrPath == null || pkgOrPath.isBlank()) return null;
        String s = pkgOrPath.trim();
        if (s.startsWith("classpath:")) s = s.substring("classpath:".length());
        s = s.replace('.', '/');
        if (!s.startsWith("/")) s = "/" + s;
        return new URI("classpath", s, null);
    }

        public static List<String> glueDistinct() {
        return new ArrayList<>(new LinkedHashSet<>(getGluePaths()));
    }
}