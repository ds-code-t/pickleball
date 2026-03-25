package io.cucumber.core.runner;

import io.cucumber.core.backend.ParameterInfo;
import io.cucumber.core.stepexpression.Argument;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;

public privileged aspect PickleStepDefinitionMatchArgumentPaddingAspect {

    before(PickleStepDefinitionMatch match) :
            execution(* io.cucumber.core.runner.PickleStepDefinitionMatch.runStep(..))
                    && this(match) {

        List<Argument> arguments = match.getArguments();
        List<ParameterInfo> parameterInfos =
                match.stepDefinition == null ? null : match.stepDefinition.parameterInfos();

        if (arguments == null || parameterInfos == null) {
            return;
        }

        int argSize = arguments.size();
        int paramSize = parameterInfos.size();

        if (argSize >= paramSize) {
            return;
        }

        for (int i = argSize; i < paramSize; i++) {
            arguments.add(new PlaceholderArgument(parameterInfos.get(i)));
        }
    }

    private static final class PlaceholderArgument implements Argument {
        private final ParameterInfo parameterInfo;

        private PlaceholderArgument(ParameterInfo parameterInfo) {
            this.parameterInfo = parameterInfo;
        }

        @Override
        public Object getValue() {
            Type type = parameterInfo == null ? Object.class : parameterInfo.getType();
            return defaultValueFor(type);
        }

        @Override
        public String toString() {
            Type type = parameterInfo == null ? Object.class : parameterInfo.getType();
            return "<missing argument placeholder for " +
                    (type == null ? "null" : type.getTypeName()) + ">";
        }
    }

    private static Object defaultValueFor(Type type) {
        Class<?> raw = rawClassOf(type);

        if (raw == null || raw == Object.class) return null;

        if (raw == String.class || CharSequence.class.isAssignableFrom(raw)) return "";

        if (raw == boolean.class || raw == Boolean.class) return false;
        if (raw == byte.class || raw == Byte.class) return (byte) 0;
        if (raw == short.class || raw == Short.class) return (short) 0;
        if (raw == int.class || raw == Integer.class) return 0;
        if (raw == long.class || raw == Long.class) return 0L;
        if (raw == float.class || raw == Float.class) return 0f;
        if (raw == double.class || raw == Double.class) return 0d;
        if (raw == char.class || raw == Character.class) return '\0';

        if (raw == Optional.class) return Optional.empty();
        if (raw == OptionalInt.class) return OptionalInt.empty();
        if (raw == OptionalLong.class) return OptionalLong.empty();
        if (raw == OptionalDouble.class) return OptionalDouble.empty();

        if (raw == List.class || raw == Collection.class || raw == Iterable.class) {
            return Collections.emptyList();
        }
        if (raw == Set.class) {
            return Collections.emptySet();
        }
        if (raw == Map.class) {
            return Collections.emptyMap();
        }
        if (raw == Stream.class) {
            return Stream.empty();
        }

        if (raw.isArray()) {
            return Array.newInstance(raw.getComponentType(), 0);
        }

        return instantiateNoArg(raw);
    }

    private static Object instantiateNoArg(Class<?> raw) {
        try {
            if (raw.isInterface() || java.lang.reflect.Modifier.isAbstract(raw.getModifiers())) {
                return null;
            }
            Constructor<?> ctor = raw.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> rawClassOf(Type type) {
        if (type == null) return Object.class;

        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }

        if (type instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) type).getRawType();
            return rawType instanceof Class<?> ? (Class<?>) rawType : Object.class;
        }

        if (type instanceof GenericArrayType) {
            Type componentType = ((GenericArrayType) type).getGenericComponentType();
            Class<?> componentClass = rawClassOf(componentType);
            return Array.newInstance(componentClass, 0).getClass();
        }

        if (type instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) type).getUpperBounds();
            if (upperBounds.length > 0) {
                return rawClassOf(upperBounds[0]);
            }
        }

        return Object.class;
    }
}