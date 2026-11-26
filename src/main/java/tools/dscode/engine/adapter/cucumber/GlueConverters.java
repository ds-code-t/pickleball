package tools.dscode.engine.adapter.cucumber;

import io.cucumber.datatable.DataTable;
import io.cucumber.datatable.DataTableType;
import io.cucumber.datatable.DataTableTypeRegistry;
import io.cucumber.datatable.DataTableTypeRegistryTableConverter;
import io.cucumber.datatable.DuplicateTypeException;
import io.cucumber.datatable.TableCellByTypeTransformer;
import io.cucumber.datatable.TableCellTransformer;
import io.cucumber.datatable.TableEntryTransformer;
import io.cucumber.datatable.TableRowTransformer;
import io.cucumber.datatable.TableTransformer;
import io.cucumber.java.DefaultDataTableCellTransformer;
import io.cucumber.java.DefaultDataTableEntryTransformer;
import tools.dscode.engine.context.ExecutionContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumer {@code @DataTableType} methods, registered while glue is scanned
 * and before any step runs. A step {@link DataTable} uses this converter, so
 * {@code asMaps} and {@code asList} are not the no-converter failure.
 */
final class GlueConverters {
    private final DataTableTypeRegistry registry;
    private final DataTable.TableConverter converter;
    private final Set<Type> defined = ConcurrentHashMap.newKeySet();

    GlueConverters(Locale locale) {
        registry = new DataTableTypeRegistry(locale == null ? Locale.ENGLISH : locale);
        converter = new DataTableTypeRegistryTableConverter(registry);
    }

    DataTable.TableConverter converter() {
        return converter;
    }

    void register(Method method) {
        if (method == null || method.isSynthetic() || method.isBridge()) return;
        if (method.getAnnotation(io.cucumber.java.DataTableType.class) != null) define(method);
        if (method.getAnnotation(DefaultDataTableEntryTransformer.class) != null) defaultEntry(method);
        if (method.getAnnotation(DefaultDataTableCellTransformer.class) != null) defaultCell(method);
    }

    private void define(Method method) {
        Type returned = method.getGenericReturnType();
        if (returned == void.class || returned == Void.class) return;
        if (method.getParameterCount() != 1) return;
        Class<?> raw = raw(method.getGenericParameterTypes()[0]);
        DataTableType type = null;
        if (raw == DataTable.class) {
            type = new DataTableType(returned, (TableTransformer<Object>) table -> invoke(method, table));
        } else if (raw == java.util.List.class) {
            type = new DataTableType(returned, (TableRowTransformer<Object>) row -> invoke(method, row));
        } else if (raw == Map.class) {
            type = new DataTableType(returned, (TableEntryTransformer<Object>) entry -> invoke(method, entry));
        } else if (raw == String.class) {
            type = new DataTableType(returned, (TableCellTransformer<Object>) cell -> invoke(method, cell));
        }
        if (type == null || !defined.add(returned)) return;
        try {
            registry.defineDataTableType(type);
        } catch (DuplicateTypeException ignored) {
            // The same glue class scanned twice keeps the first converter.
        }
    }

    private void defaultEntry(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 1 && params.length != 2) return;
        if (raw(params[0]) != Map.class) return;
        if (params.length == 2 && params[1] != Type.class) return;
        registry.setDefaultDataTableEntryTransformer((Map<String, String> entry, Type target, TableCellByTypeTransformer cell) -> {
            if (params.length == 1) return invoke(method, entry);
            return invoke(method, entry, target);
        });
    }

    private void defaultCell(Method method) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 1 && params.length != 2) return;
        if (params[0] != String.class) return;
        if (params.length == 2 && params[1] != Type.class) return;
        registry.setDefaultDataTableCellTransformer((String cell, Type target) -> {
            if (params.length == 1) return invoke(method, cell);
            return invoke(method, cell, target);
        });
    }

    private static Object invoke(Method method, Object... args) {
        Object target = null;
        if (!Modifier.isStatic(method.getModifiers())) {
            target = instance(method.getDeclaringClass());
        }
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Object instance(Class<?> type) {
        return instances().computeIfAbsent(type, GlueConverters::construct);
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<?>, Object> instances() {
        ExecutionContext context = ExecutionContext.current();
        if (context == null) return new IdentityHashMap<>();
        Object existing = context.resources().get("instances");
        if (existing instanceof Map<?, ?> map) return (Map<Class<?>, Object>) map;
        Map<Class<?>, Object> created = new IdentityHashMap<>();
        context.resource("instances", created);
        return created;
    }

    private static Object construct(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> clazz) return clazz;
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return Object.class;
    }
}
