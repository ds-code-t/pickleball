package io.cucumber.java;

import io.cucumber.core.backend.Glue;
import io.cucumber.core.resource.ClasspathSupport;

import java.net.URI;
import java.util.Collection;
import java.util.List;

import static io.cucumber.core.resource.ClasspathSupport.CLASSPATH_SCHEME;

/** Traces what JavaBackend scans and finds during glue loading. */
public privileged aspect JavaBackend_ScanTrace {

    Object around(JavaBackend backend, Glue glue, List<URI> gluePaths) :
            execution(void io.cucumber.java.JavaBackend.loadGlue(io.cucumber.core.backend.Glue, java.util.List))
                    && this(backend) && args(glue, gluePaths) {

        System.out.println("## JavaBackend.loadGlue: gluePaths=" + gluePaths);
        try {
            for (URI uri : gluePaths) {
                if (!CLASSPATH_SCHEME.equals(uri.getScheme())) continue;
                String pkg = ClasspathSupport.packageName(uri);
                System.out.println("## scanning pkg: " + pkg);
                Collection<Class<?>> classes = backend.classFinder.scanForClassesInPackage(pkg);
                System.out.println("## found " + classes.size() + " classes in " + pkg);
                for (Class<?> c : classes) {
                    System.out.println("##  - " + c.getName());
                }
            }
        } catch (Throwable t) {
            System.out.println("## JavaBackend_ScanTrace error: " + t);
        }
        return proceed(backend, glue, gluePaths);
    }
}
