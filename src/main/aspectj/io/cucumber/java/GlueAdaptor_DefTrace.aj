package io.cucumber.java;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/** Traces registrations via GlueAdaptor. */
public privileged aspect GlueAdaptor_DefTrace {

    void around(GlueAdaptor self, Method method, Annotation annotation) :
            execution(void io.cucumber.java.GlueAdaptor.addDefinition(java.lang.reflect.Method, java.lang.annotation.Annotation))
                    && this(self) && args(method, annotation) {

        System.out.println(">> register def: " + method.getDeclaringClass().getName()
                + "#" + method.getName()
                + " @" + annotation.annotationType().getName());
        proceed(self, method, annotation);
    }
}
