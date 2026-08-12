package com.example.pickleball.support;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class InternalJavaTestRunner {
    private InternalJavaTestRunner() {
    }

    public static List<Result> run(Class<?>... testClasses) {
        List<Result> results = new ArrayList<>();
        Arrays.stream(testClasses)
                .sorted(Comparator.comparing(Class::getName))
                .forEach(testClass -> runClass(testClass, results));
        return List.copyOf(results);
    }

    private static void runClass(Class<?> testClass, List<Result> results) {
        Object instance = newInstance(testClass);
        Arrays.stream(testClass.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class))
                .sorted(Comparator.comparing(Method::getName))
                .forEach(method -> results.add(runMethod(instance, method)));
    }

    private static Object newInstance(Class<?> testClass) {
        try {
            Constructor<?> constructor = testClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Could not create internal test class " + testClass.getName(),
                    exception
            );
        }
    }

    private static Result runMethod(Object instance, Method method) {
        String name = method.getDeclaringClass().getSimpleName()
                + "." + method.getName();
        try {
            method.setAccessible(true);
            method.invoke(instance);
            return new Result(name, null);
        } catch (InvocationTargetException exception) {
            Throwable failure = exception.getCause() == null
                    ? exception
                    : exception.getCause();
            return new Result(name, failure);
        } catch (ReflectiveOperationException exception) {
            return new Result(name, exception);
        }
    }

    public record Result(String name, Throwable failure) {
        public boolean passed() {
            return failure == null;
        }

        public String display() {
            return passed()
                    ? "PASS " + name
                    : "FAIL " + name + ": " + failure.getMessage();
        }
    }
}
