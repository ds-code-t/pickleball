package io.cucumber.core.runner;

import io.cucumber.core.backend.StepDefinition;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.runner.CachingGlue;
import io.cucumber.core.runner.PickleStepDefinitionMatch;
import io.cucumber.core.stepexpression.Argument;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getGlobalCachingGlue;

public final class CucumberStepInvoker {

    private static final URI SYNTHETIC_URI = URI.create("memory:/dynamic-step");

    private CucumberStepInvoker() {
    }

    public static Method findMethod(CachingGlue glue, String stepText) {
        try {
            Object javaStepDefinition = getJavaStepDefinition(glue, stepText);
            return (Method) findField(javaStepDefinition.getClass(), "method").get(javaStepDefinition);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Resolves one Gherkin step text against current Cucumber glue without invoking it.
     * Returns empty when nothing matches. Ambiguous matches still throw.
     */
    public static Optional<GlueDefinition> findGlueDefinition(String stepText) {
        CachingGlue glue = currentGlue();
        if (glue == null || stepText == null) return Optional.empty();
        return findGlueDefinition(glue, stepText);
    }

    public static Optional<GlueDefinition> findGlueDefinition(CachingGlue glue, String stepText) {
        if (glue == null || stepText == null) return Optional.empty();
        try {
            PickleStepDefinitionMatch match;
            synchronized (glue) {
                match = getMatch(glue, stepText);
            }
            if (match == null) return Optional.empty();

            Method method = null;
            String pattern = "";
            try {
                Object javaStepDefinition = getJavaStepDefinition(match);
                if (javaStepDefinition != null) {
                    try {
                        method = (Method) findField(javaStepDefinition.getClass(), "method")
                                .get(javaStepDefinition);
                    } catch (Exception ignored) {
                    }
                    if (javaStepDefinition instanceof StepDefinition stepDefinition
                            && stepDefinition.getPattern() != null) {
                        pattern = stepDefinition.getPattern();
                    }
                }
            } catch (Exception ignored) {
            }
            if (pattern.isBlank()) {
                try {
                    StepDefinition definition = match.getStepDefinition();
                    if (definition != null && definition.getPattern() != null) {
                        pattern = definition.getPattern();
                    }
                } catch (Exception ignored) {
                }
            }
            if (method == null && pattern.isBlank()) return Optional.empty();
            return Optional.of(new GlueDefinition(method, pattern));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    public record GlueDefinition(Method method, String pattern) {
        public GlueDefinition {
            pattern = pattern == null ? "" : pattern;
        }
    }

    public static Object runPhaseStep(String stepText, Object... extraArgs) {
        return runStepWithArgs( stepText, extraArgs);
    }

    public static Object runStepWithArgs(String stepText, Object... extraArgs) {
        CachingGlue glue =  getGlobalCachingGlue();
        if (glue == null) {
            throw new RuntimeException("Global CachingGlue is null");
        }

        return invoke(glue, stepText, extraArgs);
    }
    public static Object invoke(CachingGlue glue, String stepText, Object... extraArgs) {
        try {
            PickleStepDefinitionMatch match = getMatch(glue, stepText);
            Object javaStepDefinition = getJavaStepDefinition(match);

            List<Object> mergedArgs = new ArrayList<>();
            for (Argument argument : match.getArguments()) {
                mergedArgs.add(argument.getValue());
            }
            if (extraArgs != null) {
                for (Object extraArg : extraArgs) {
                    mergedArgs.add(extraArg);
                }
            }

            Method invokeMethod = findMethod(javaStepDefinition.getClass(), "invokeMethod", Object[].class);
            return invokeMethod.invoke(javaStepDefinition, (Object) mergedArgs.toArray(Object[]::new));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CachingGlue currentGlue() {
        CurrentScenarioState state = getCurrentScenarioState();
        if (state != null && state.cachingGlue != null) return state.cachingGlue;
        try {
            return getGlobalCachingGlue();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static PickleStepDefinitionMatch getMatch(CachingGlue glue, String stepText) throws Exception {
//        Method prepareGlue = findMethod(glue.getClass(), "prepareGlue", Locale.class);
//        prepareGlue.invoke(glue, Locale.getDefault());
        Method stepDefinitionMatch = findMethod(glue.getClass(), "stepDefinitionMatch", URI.class, Step.class);
        try {
            return (PickleStepDefinitionMatch) stepDefinitionMatch.invoke(
                    glue, SYNTHETIC_URI, stepProxy(stepText)
            );
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw failure;
        }
    }

    private static Object getJavaStepDefinition(CachingGlue glue, String stepText) throws Exception {
        return getJavaStepDefinition(getMatch(glue, stepText));
    }

    private static Object getJavaStepDefinition(PickleStepDefinitionMatch match) throws Exception {
        Method getCoreStepDefinition = findMethod(match.getClass(), "getStepDefinition");
        Object coreStepDefinition = getCoreStepDefinition.invoke(match);

        Method getWrappedStepDefinition = findMethod(coreStepDefinition.getClass(), "getStepDefinition");
        return getWrappedStepDefinition.invoke(coreStepDefinition);
    }

    private static Step stepProxy(String stepText) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getText" -> stepText;
            case "getLine" -> 1;
            case "getId" -> "dynamic-step";
            default -> defaultValue(method.getReturnType());
        };

        return (Step) Proxy.newProxyInstance(
                Step.class.getClassLoader(),
                new Class<?>[]{Step.class},
                handler
        );
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) throws Exception {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Field findField(Class<?> type, String name) throws Exception {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }
}