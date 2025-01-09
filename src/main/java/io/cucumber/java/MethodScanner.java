/*
 * This file incorporates work covered by the following copyright and permission notice:
 *
 * Copyright (c) Cucumber Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.cucumber.java;

import io.cucumber.core.logging.Logger;
import io.cucumber.core.logging.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;

import static io.cucumber.core.resource.ClasspathSupport.classPathScanningExplanation;
import static io.cucumber.java.InvalidMethodException.createInvalidMethodException;
import static java.lang.reflect.Modifier.isAbstract;
import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;

final class MethodScanner {

    private static final Logger log = LoggerFactory.getLogger(MethodScanner.class);

    private MethodScanner() {
    }

    static void scan(Class<?> aClass, BiConsumer<Method, Annotation> consumer) {
        // prevent unnecessary checking of Object methods
        if (Object.class.equals(aClass)) {
            return;
        }

        if (!isInstantiable(aClass)) {
            return;
        }
        for (Method method : safelyGetMethods(aClass)) {
            scan(consumer, aClass, method);
        }
    }

    private static Method[] safelyGetMethods(Class<?> aClass) {
        try {
            return aClass.getMethods();
        } catch (NoClassDefFoundError e) {
            log.warn(e,
                () -> "Failed to load methods of class '" + aClass.getName() + "'.\n" + classPathScanningExplanation());
        }
        return new Method[0];
    }

    private static boolean isInstantiable(Class<?> clazz) {
        return isPublic(clazz.getModifiers())
                && !isAbstract(clazz.getModifiers())
                && (isStatic(clazz.getModifiers()) || clazz.getEnclosingClass() == null);
    }

    private static void scan(BiConsumer<Method, Annotation> consumer, Class<?> aClass, Method method) {
        // prevent unnecessary checking of Object methods
        if (Object.class.equals(method.getDeclaringClass())) {
            return;
        }

        // exclude bridge methods: when a class implements a method
        // from the interface but specializes the return type, two methods will
        // be generated. One with the return type of the interface and one
        // with the specialized return type. The former is a bridge method.
        // Depending on the JVM, the method annotations are also applied to
        // the bridge method.
        if (method.isBridge()) {
            return;
        }

        scan(consumer, aClass, method, method.getAnnotations());
    }

    private static void scan(
            BiConsumer<Method, Annotation> consumer, Class<?> aClass, Method method, Annotation[] methodAnnotations
    ) {
        for (Annotation annotation : methodAnnotations) {
            if (isHookAnnotation(annotation) || isStepDefinitionAnnotation(annotation)) {
                validateMethod(aClass, method);
                consumer.accept(method, annotation);
            } else if (isRepeatedStepDefinitionAnnotation(annotation)) {
                scan(consumer, aClass, method, repeatedAnnotations(annotation));
            }
        }
    }

    private static void validateMethod(Class<?> glueCodeClass, Method method) {
        if (!glueCodeClass.equals(method.getDeclaringClass())) {
            throw createInvalidMethodException(method, glueCodeClass);
        }
    }

    private static boolean isHookAnnotation(Annotation annotation) {
        Class<? extends Annotation> annotationClass = annotation.annotationType();
        return annotationClass.equals(Before.class)
                || annotationClass.equals(BeforeAll.class)
                || annotationClass.equals(After.class)
                || annotationClass.equals(AfterAll.class)
                || annotationClass.equals(BeforeStep.class)
                || annotationClass.equals(AfterStep.class)
                || annotationClass.equals(ParameterType.class)
                || annotationClass.equals(DataTableType.class)
                || annotationClass.equals(DefaultParameterTransformer.class)
                || annotationClass.equals(DefaultDataTableEntryTransformer.class)
                || annotationClass.equals(DefaultDataTableCellTransformer.class)
                || annotationClass.equals(DocStringType.class);
    }

    private static boolean isStepDefinitionAnnotation(Annotation annotation) {
        Class<? extends Annotation> annotationClass = annotation.annotationType();
        return annotationClass.getAnnotation(StepDefinitionAnnotation.class) != null;
    }

    private static boolean isRepeatedStepDefinitionAnnotation(Annotation annotation) {
        Class<? extends Annotation> annotationClass = annotation.annotationType();
        return annotationClass.getAnnotation(StepDefinitionAnnotations.class) != null;
    }

    private static Annotation[] repeatedAnnotations(Annotation annotation) {
        try {
            Method expressionMethod = annotation.getClass().getMethod("value");
            return (Annotation[]) Invoker.invoke(annotation, expressionMethod);
        } catch (NoSuchMethodException e) {
            // Should never happen.
            throw new IllegalStateException(e);
        }
    }

}
