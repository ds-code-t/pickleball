package io.cucumber.core.gherkin.messages;

import io.cucumber.messages.types.PickleStep;
import sun.misc.Unsafe;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.time.temporal.Temporal;
import java.util.*;

public final class CucumberDeepCloneUtil {

    private static final Unsafe UNSAFE = initUnsafe();

    private CucumberDeepCloneUtil() {
    }

    static PickleStep deepClonePickleStep(PickleStep source) {
        if (source == null) {
            return null;
        }

        IdentityHashMap<Object, Object> seen = new IdentityHashMap<>();

        PickleStep clone =  new PickleStep(
                deepCloneAny(source.getArgument().orElse(null), seen),
                new ArrayList<>(source.getAstNodeIds()),
                source.getId(),
                source.getType().orElse(null),
                source.getText()
        );
        clone.metaText = source.metaText;
        clone.nestingLevel = source.nestingLevel;

        return clone;
    }

    public static GherkinMessagesStep deepCloneGherkinMessagesStep(io.cucumber.plugin.event.Step inputStep) {
        if (inputStep == null) {
            return null;
        }
        GherkinMessagesStep source = (GherkinMessagesStep) inputStep;
        IdentityHashMap<Object, Object> seen = new IdentityHashMap<>();

        GherkinMessagesStep clone = allocateInstance(GherkinMessagesStep.class);

        setField(clone, "pickleStep", deepClonePickleStep(getField(source, "pickleStep")));
        setField(clone, "argument", deepCloneAny(source.getArgument(), seen));
        setField(clone, "keyWord", source.getKeyword());
        setField(clone, "stepType", source.getType());
        setField(clone, "previousGwtKeyWord", source.getPreviousGivenWhenThenKeyword());
        setField(clone, "location", deepCloneAny(source.getLocation(), seen));

        return clone;
    }

    @SuppressWarnings("unchecked")
    private static <T> T deepCloneAny(T source, IdentityHashMap<Object, Object> seen) {
        if (source == null) {
            return null;
        }

        if (isKnownImmutable(source.getClass())) {
            return source;
        }

        Object existing = seen.get(source);
        if (existing != null) {
            return (T) existing;
        }

        if (source instanceof Optional<?> optional) {
            return (T) optional.map(v -> deepCloneAny(v, seen));
        }

        Class<?> type = source.getClass();

        if (type.isArray()) {
            int length = Array.getLength(source);
            Object copy = Array.newInstance(type.getComponentType(), length);
            seen.put(source, copy);

            for (int i = 0; i < length; i++) {
                Array.set(copy, i, deepCloneAny(Array.get(source, i), seen));
            }
            return (T) copy;
        }

        if (source instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            seen.put(source, copy);
            for (Object item : list) {
                copy.add(deepCloneAny(item, seen));
            }
            return (T) maybeWrapUnmodifiableList(source, copy);
        }

        if (source instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>(Math.max(16, set.size() * 2));
            seen.put(source, copy);
            for (Object item : set) {
                copy.add(deepCloneAny(item, seen));
            }
            return (T) maybeWrapUnmodifiableSet(source, copy);
        }

        if (source instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>(Math.max(16, map.size() * 2));
            seen.put(source, copy);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(
                        deepCloneAny(entry.getKey(), seen),
                        deepCloneAny(entry.getValue(), seen)
                );
            }
            return (T) maybeWrapUnmodifiableMap(source, copy);
        }

        Object copy = allocateInstance(type);
        seen.put(source, copy);

        for (Field field : allInstanceFields(type)) {
            Object fieldValue = getFieldValue(field, source);
            Object fieldCopy = deepCloneAny(fieldValue, seen);
            setFieldValue(field, copy, fieldCopy);
        }

        return (T) copy;
    }

    private static boolean isKnownImmutable(Class<?> type) {
        return type.isPrimitive()
                || type.isEnum()
                || type == String.class
                || type == Boolean.class
                || type == Character.class
                || Number.class.isAssignableFrom(type)
                || type == Class.class
                || type == URI.class
                || UUID.class.isAssignableFrom(type)
                || Temporal.class.isAssignableFrom(type)
                || type.getName().startsWith("java.time.");
    }

    private static List<Field> allInstanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to read field '" + fieldName + "'", e);
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to set field '" + fieldName + "'", e);
        }
    }

    private static Object getFieldValue(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed reading field " + field, e);
        }
    }

    private static void setFieldValue(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed writing field " + field, e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // keep walking up the hierarchy
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + fieldName);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocateInstance(Class<T> type) {
        try {
            return (T) UNSAFE.allocateInstance(type);
        } catch (InstantiationException e) {
            throw new RuntimeException("Failed to allocate instance of " + type.getName(), e);
        }
    }

    private static Unsafe initUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Unable to access sun.misc.Unsafe", e);
        }
    }

    private static Object maybeWrapUnmodifiableList(Object original, List<Object> copy) {
        String name = original.getClass().getName();
        if (name.contains("Unmodifiable") || name.startsWith("java.util.ImmutableCollections$")) {
            return Collections.unmodifiableList(copy);
        }
        return copy;
    }

    private static Object maybeWrapUnmodifiableSet(Object original, Set<Object> copy) {
        String name = original.getClass().getName();
        if (name.contains("Unmodifiable") || name.startsWith("java.util.ImmutableCollections$")) {
            return Collections.unmodifiableSet(copy);
        }
        return copy;
    }

    private static Object maybeWrapUnmodifiableMap(Object original, Map<Object, Object> copy) {
        String name = original.getClass().getName();
        if (name.contains("Unmodifiable") || name.startsWith("java.util.ImmutableCollections$")) {
            return Collections.unmodifiableMap(copy);
        }
        return copy;
    }
}